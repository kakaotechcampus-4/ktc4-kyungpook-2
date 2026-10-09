package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.domain.Organization;
import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.exception.SummaryTargetException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.InProgressKey;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.SummaryCandidate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 요약 워커가 쓰는 묶음 집기·요청 조립. AI 호출은 트랜잭션 밖에서 하도록 단계마다 트랜잭션을 나눈다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    /** 아직 자동 처리 중인 상태. 이 일지들이 끝나야 같은 기관·날짜를 묶는다. 사람 손이 필요한 상태는 기다리지 않는다. */
    static final Set<JournalEntryStatus> IN_PROGRESS = Set.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING,
            JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ChildRepository childRepository;
    private final OrganizationRepository organizationRepository;
    private final SummaryRunRequests runRequests;
    private final SummaryProperties properties;
    private final Clock clock;

    /**
     * 요약할 준비가 된 묶음을 집어 가며 그 안의 일지를 SUMMARIZING 으로 바꾼다 (AI/summary/CRITERIA.md §5).
     *
     * <p>준비 조건은 셋이다. 마감(entry_date 다음 날 03:00)이 지났고, 묶음의 마지막 일지가 들어온 뒤 디바운스(30분)가
     * 지났고, 같은 기관·같은 날짜에 매칭·검증 중인 일지가 없어야 한다. "지금 요약" 을 누른 묶음은 앞의 둘을 건너뛴다.
     *
     * <p>새로 검증을 통과한 일지(VALIDATED)가 있는 묶음만 집는다. 승인 전 요약에 이미 들어간 일지(GATE1_PENDING)는
     * 그 새 일지와 함께 다시 요약하도록 같이 담는다.
     */
    @Transactional
    public List<ClaimedSummaryGroup> claimReadyGroups(int limit) {
        Set<InProgressKey> busy = Set.copyOf(journalEntryRepository.findInProgressKeys(IN_PROGRESS));
        // 새 일지(VALIDATED)로 묶음을 먼저 정한다. 승인 대기(GATE1_PENDING) 일지는 Gate 1 이 처리하기 전까지
        // 계속 쌓이므로 매번 전부 읽지 않고, 집기로 한 묶음의 것만 가져온다.
        Map<SummaryGroup, List<SummaryCandidate>> groups = groupCandidates(
                journalEntryRepository.findSummaryCandidates(List.of(JournalEntryStatus.VALIDATED)));

        List<ClaimedSummaryGroup> claimed = new ArrayList<>();
        for (Map.Entry<SummaryGroup, List<SummaryCandidate>> e : groups.entrySet()) {
            if (claimed.size() >= limit) {
                break;
            }
            SummaryGroup group = e.getKey();
            List<SummaryCandidate> members = e.getValue();
            if (!isReady(group, members, busy)) {
                continue;
            }
            List<Long> pending = journalEntryRepository.findGroupEntryIds(JournalEntryStatus.GATE1_PENDING,
                    group.childId(), group.entryDate(), members.get(0).institutionId());
            List<Long> ids = Stream.concat(pending.stream(), members.stream().map(SummaryCandidate::journalEntryId))
                    .sorted()
                    .toList();
            journalEntryRepository.findAllById(ids).forEach(JournalEntry::startSummarizing);
            claimed.add(new ClaimedSummaryGroup(group, ids));
        }
        return claimed;
    }

    private Map<SummaryGroup, List<SummaryCandidate>> groupCandidates(List<SummaryCandidate> candidates) {
        Map<SummaryGroup, List<SummaryCandidate>> groups = new LinkedHashMap<>();
        for (SummaryCandidate c : candidates) {
            Long institutionId = parseInstitutionId(c);
            if (institutionId == null) {
                continue;
            }
            groups.computeIfAbsent(new SummaryGroup(c.childId(), c.entryDate(), institutionId), k -> new ArrayList<>())
                    .add(c);
        }
        return groups;
    }

    private boolean isReady(SummaryGroup group, List<SummaryCandidate> members, Set<InProgressKey> busy) {
        // 기관·날짜만 같아도 기다린다 — 매칭 전 일지는 아동이 비어 있어 이 아이 일지인지 아직 모른다.
        String institutionId = members.get(0).institutionId();
        if (busy.contains(new InProgressKey(institutionId, group.entryDate()))) {
            return false;
        }
        Instant now = clock.instant();
        if (runRequests.isRequested(group, now)) {
            return true;
        }
        Instant cutoff = properties.cutoffOf(group.entryDate());
        // createdAt 은 서버 기본 시간대의 LocalDateTime.now() 로 채워진다.
        LocalDateTime lastUploaded = members.stream().map(SummaryCandidate::createdAt)
                .max(LocalDateTime::compareTo).orElseThrow();
        Instant settled = lastUploaded.atZone(ZoneId.systemDefault()).toInstant().plus(properties.debounce());
        return !now.isBefore(cutoff) && !now.isBefore(settled);
    }

    /**
     * 집어 간 묶음의 요약 요청을 만든다. AI 입력은 저장하지 않고 그때그때 조립한다 (DB 스키마 §2.2).
     * 그새 삭제된 일지는 뺀다.
     */
    @Transactional(readOnly = true)
    public SummaryAgentRequest prepareRequest(ClaimedSummaryGroup claimed) {
        SummaryGroup group = claimed.group();
        List<JournalEntry> entries = journalEntryRepository.findAllById(claimed.journalEntryIds()).stream()
                .filter(entry -> !entry.isDeleted())
                .sorted(Comparator.comparing(JournalEntry::getId))
                .toList();
        if (entries.isEmpty()) {
            throw new SummaryTargetException("no journal entry left in summary group: " + group);
        }
        // 일지가 묶인 뒤에 아동·기관이 삭제 표시돼도 이 요약의 주인공·작성 기관은 그대로라 이름을 싣는다.
        String childName = childRepository.findById(group.childId()).map(Child::getName).orElse(null);
        String institutionName = organizationRepository.findById(group.institutionId())
                .map(Organization::getName).orElse(null);
        String entryDate = group.entryDate().toString();

        List<SummaryAgentRequest.Source> sources = entries.stream()
                .map(entry -> new SummaryAgentRequest.Source(entry.getId(), entry.getContent(), entryDate))
                .toList();
        return new SummaryAgentRequest(group.childId(), childName, entryDate, group.institutionId(),
                institutionName, sources, otherChildNames(group.childId(), entries));
    }

    /**
     * 묶음의 일지 본문에 이름이 나온 아이들(matching_result.mentioned_child_ids)을 합쳐 주인공을 뺀 이름 목록.
     * 명부에서 빠진 아이도 이름이 새면 안 되므로 삭제 여부와 상관없이 찾는다.
     */
    private List<String> otherChildNames(Long childId, List<JournalEntry> entries) {
        // 재처리로 매칭 결과가 쌓였으면 일지마다 가장 최근 것을 쓴다.
        Map<Long, MatchingResult> latest = new HashMap<>();
        for (MatchingResult result : matchingResultRepository.findByJournalEntryIdIn(
                entries.stream().map(JournalEntry::getId).toList())) {
            latest.merge(result.getJournalEntryId(), result, (a, b) -> a.getId() > b.getId() ? a : b);
        }
        Set<Long> others = new TreeSet<>();
        latest.values().forEach(result -> others.addAll(parseIds(result.getMentionedChildIds())));
        others.remove(childId);
        if (others.isEmpty()) {
            return List.of();
        }
        return childRepository.findAllById(others).stream()
                .sorted(Comparator.comparing(Child::getId))
                .map(Child::getName)
                .distinct()
                .toList();
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = new ArrayList<>();
            MAPPER.readTree(json).forEach(node -> ids.add(node.asLong()));
            return ids;
        } catch (JsonProcessingException e) {
            log.warn("mentioned_child_ids is not a JSON array: {}", json);
            return List.of();
        }
    }

    /** 집어 갔지만 처리하지 않은 묶음을 요약하기 전 상태로 돌려놓는다. 이미 다른 상태로 바뀐 일지는 건드리지 않는다. */
    @Transactional
    public void release(List<ClaimedSummaryGroup> groups) {
        List<Long> ids = groups.stream().flatMap(g -> g.journalEntryIds().stream()).toList();
        journalEntryRepository.findAllById(ids).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.SUMMARIZING)
                .forEach(JournalEntry::releaseSummarizing);
    }

    /** 이전에 꺼진 앱이 요약하다 만 일지를 되돌린다. 워커가 첫 실행에서 한 번만 부른다. */
    @Transactional
    public int releaseStuck() {
        List<JournalEntry> stuck = journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.SUMMARIZING);
        stuck.forEach(JournalEntry::releaseSummarizing);
        return stuck.size();
    }

    private Long parseInstitutionId(SummaryCandidate c) {
        try {
            return Long.valueOf(c.institutionId());
        } catch (NumberFormatException e) {
            log.warn("summary candidate skipped: institution id is not numeric journalEntryId={} institutionId={}",
                    c.journalEntryId(), c.institutionId());
            return null;
        }
    }
}
