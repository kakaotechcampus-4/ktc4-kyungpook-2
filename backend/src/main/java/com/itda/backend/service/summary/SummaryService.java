package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
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
    private static final Set<JournalEntryStatus> IN_PROGRESS = Set.of(
            JournalEntryStatus.PENDING, JournalEntryStatus.MATCHING,
            JournalEntryStatus.MATCHED, JournalEntryStatus.VALIDATING);

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
        Map<SummaryGroup, List<SummaryCandidate>> groups = groupCandidates(journalEntryRepository.findSummaryCandidates(
                List.of(JournalEntryStatus.VALIDATED, JournalEntryStatus.GATE1_PENDING)));

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
            List<Long> ids = members.stream().map(SummaryCandidate::journalEntryId).toList();
            journalEntryRepository.findAllById(ids).forEach(JournalEntry::startSummarizing);
            runRequests.remove(group);
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
        boolean hasNewEntry = members.stream().anyMatch(c -> c.status() == JournalEntryStatus.VALIDATED);
        if (!hasNewEntry) {
            return false;
        }
        // 기관·날짜만 같아도 기다린다 — 매칭 전 일지는 아동이 비어 있어 이 아이 일지인지 아직 모른다.
        String institutionId = members.get(0).institutionId();
        if (busy.contains(new InProgressKey(institutionId, group.entryDate()))) {
            return false;
        }
        if (runRequests.contains(group)) {
            return true;
        }
        Instant now = clock.instant();
        Instant cutoff = group.entryDate().plusDays(1).atTime(properties.cutoffTime())
                .atZone(properties.zone()).toInstant();
        // createdAt 은 서버 기본 시간대의 LocalDateTime.now() 로 채워진다.
        LocalDateTime lastUploaded = members.stream().map(SummaryCandidate::createdAt)
                .max(LocalDateTime::compareTo).orElseThrow();
        Instant settled = lastUploaded.atZone(ZoneId.systemDefault()).toInstant().plus(properties.debounce());
        return !now.isBefore(cutoff) && !now.isBefore(settled);
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
