package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.LocalDateTime;
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
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.matching.MatchingResult;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.exception.summary.SummaryTargetException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.InProgressKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.SummaryCandidate;
import com.itda.backend.repository.summary.SummaryResultRepository;

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
    private final SummaryResultRepository summaryResultRepository;
    private final SummaryRunRequests runRequests;
    private final Clock clock;

    /**
     * 요약할 준비가 된 묶음을 집어 가며 그 안의 일지를 SUMMARIZING 으로 바꾼다 (AI/summary/CRITERIA.md §5).
     *
     * <p>준비 조건은 둘이다. 교사가 그 묶음에 [요약]을 눌렀고({@link SummaryRunRequests}), 같은 기관·같은 날짜에
     * 매칭·검증 중인 일지가 없어야 한다. 누르지 않은 묶음은 요약하지 않는다 — 자동 요약은 없다 (#181).
     * 요약할 일지가 없게 된 요청은 이때 거둔다.
     *
     * <p>새로 검증을 통과한 일지(VALIDATED)가 있는 묶음만 집는다. 그 묶음의 최신 판이 아직 Gate 1 대기면, 그 판에 든
     * 일지(GATE1_PENDING)도 새 일지와 함께 다시 요약하도록 같이 담는다. 보류된 일지(GATE1_HELD)와 보류 해제된 옛 판의
     * 일지는 담지 않는다 — 다른 판의 일지가 섞이면 한 일지가 두 요약에 올라간다.
     */
    @Transactional
    public List<ClaimedSummaryGroup> claimReadyGroups(int limit) {
        // 집은 시각과 요청을 일지보다 먼저 읽는다. 아래에서 일지를 읽는 사이 새로 올리고 다시 누른 요청은 이 시각보다
        // 늦어 이번에 처리하지도 거두지도 않는다 — 그 일지는 이번 요약에 들어 있지 않을 수 있다.
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        Map<SummaryGroup, LocalDateTime> requests = runRequests.pendingRequests();
        Set<InProgressKey> busy = Set.copyOf(journalEntryRepository.findInProgressKeys(IN_PROGRESS));
        // 새 일지(VALIDATED)로 묶음을 먼저 정한다. 승인 대기(GATE1_PENDING) 일지는 Gate 1 이 처리하기 전까지
        // 계속 쌓이므로 매번 전부 읽지 않고, 집기로 한 묶음의 것만 가져온다.
        Map<SummaryGroup, List<SummaryCandidate>> groups = groupCandidates(
                journalEntryRepository.findSummaryCandidates(List.of(JournalEntryStatus.VALIDATED)));
        cancelStaleRequests(requests, groups.keySet(), busy);

        List<ClaimedSummaryGroup> claimed = new ArrayList<>();
        for (Map.Entry<SummaryGroup, List<SummaryCandidate>> e : groups.entrySet()) {
            if (claimed.size() >= limit) {
                break;
            }
            SummaryGroup group = e.getKey();
            List<SummaryCandidate> members = e.getValue();
            if (!isReady(group, members, busy, requests.keySet())) {
                continue;
            }
            List<Long> pending = findLatestPendingEntryIds(group);
            List<Long> ids = Stream.concat(pending.stream(), members.stream().map(SummaryCandidate::journalEntryId))
                    .sorted()
                    .toList();
            journalEntryRepository.findAllById(ids).forEach(JournalEntry::startSummarizing);
            claimed.add(new ClaimedSummaryGroup(group, ids, claimedAt));
        }
        return claimed;
    }

    /**
     * 요약할 일지가 없게 된 [요약] 요청을 거둔다. 매칭·검증 중이라 받아 둔 요청인데, 끝나고 보니 이 묶음에 새 일지가
     * 없는 경우다 (다른 아이로 매칭됐거나 BLOCK). 남겨 두면 한참 뒤 그 날짜로 올라온 일지가 누르지 않았는데 요약된다.
     *
     * <p>요약 중(SUMMARIZING)에 멈춘 묶음은 거두지 않는다. 결과 저장이 실패한 것이라, 재시작해 요약 대기로 돌아가면
     * 다시 요약해야 한다. 읽어 둔 누른 시각까지만 거둬서, 그 뒤에 다시 누른 요청은 남는다.
     */
    private void cancelStaleRequests(Map<SummaryGroup, LocalDateTime> requests, Set<SummaryGroup> candidates,
            Set<InProgressKey> busy) {
        List<SummaryGroup> stale = requests.keySet().stream()
                .filter(group -> !candidates.contains(group))
                .filter(group -> !busy.contains(
                        new InProgressKey(String.valueOf(group.institutionId()), group.entryDate())))
                .toList();
        if (stale.isEmpty()) {
            return;
        }
        Set<SummaryGroup> summarizing = groupCandidates(
                journalEntryRepository.findSummaryCandidates(List.of(JournalEntryStatus.SUMMARIZING))).keySet();
        stale.stream()
                .filter(group -> !summarizing.contains(group))
                .forEach(group -> runRequests.markHandled(group, requests.get(group)));
    }

    /**
     * 그 묶음의 최신 판이 승인 전이면 그 판에 든 Gate 1 대기 일지 id. 레코더도 같은 조건({@link SummaryResult#isBeforeApproval})으로
     * 최신 판을 덮어쓴다.
     * 묶음의 판을 잠근 뒤에 읽는다 — 커밋 전인 Gate 1 결정을 기다렸다가 결정 뒤 상태로 고른다.
     */
    private List<Long> findLatestPendingEntryIds(SummaryGroup group) {
        return summaryResultRepository.findGroupForUpdate(group.childId(), group.entryDate(), group.institutionId())
                .stream().findFirst()
                .filter(SummaryResult::isBeforeApproval)
                .map(latest -> journalEntryRepository.findIdsBySummaryIdAndStatus(
                        latest.getId(), JournalEntryStatus.GATE1_PENDING))
                .orElse(List.of());
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

    private boolean isReady(SummaryGroup group, List<SummaryCandidate> members, Set<InProgressKey> busy,
            Set<SummaryGroup> requested) {
        // 기관·날짜만 같아도 기다린다 — 매칭 전 일지는 아동이 비어 있어 이 아이 일지인지 아직 모른다.
        String institutionId = members.get(0).institutionId();
        if (busy.contains(new InProgressKey(institutionId, group.entryDate()))) {
            return false;
        }
        return requested.contains(group);
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
