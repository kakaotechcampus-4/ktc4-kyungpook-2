package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.dto.response.summary.FailedSummaryGroupResponse;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryErrorCode;
import com.itda.backend.exception.summary.SummaryException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.record.GroupKey;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 요약 실패({@link JournalEntryStatus#SUMMARY_FAILED}) 묶음을 검토 화면에 올리고, 교사가 [다시 요약] 하거나
 * [요약 없이 마무리] 한다 (#369). 요약 행이 없어 묶음(아동 × 날짜 × 기관)을 일지로 정한다.
 *
 * <p>보이는 범위는 Gate 1 목록과 같다 — 요청한 교사의 기관 일지이고, 그 기관 동의가 켜진 아이만 (#332).
 * 시스템 오류({@code SYSTEM_ERROR})는 다시 돌려도 같으므로 여기서 다루지 않는다 (관리자 처리).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryFailureService {

    /** 요약 실패 원인. 지금은 근거가 남은 문장이 없는 경우(AI 503 no_grounded_claims)뿐이다. */
    static final String REASON_NO_GROUNDED_CLAIMS = "NO_GROUNDED_CLAIMS";

    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final ChildRepository childRepository;
    private final ConsentStateReader consentStateReader;
    private final SummaryRunRequests runRequests;
    private final SummaryProperties properties;
    private final Clock clock;

    /** 요청 교사 기관의 요약 실패 묶음을 오래된 날짜부터. 같은 날짜는 아동 id 순이다. */
    @Transactional(readOnly = true)
    public List<FailedSummaryGroupResponse> getFailedGroups(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<JournalEntry> entries = journalEntryRepository.findByInstitutionAndStatus(
                String.valueOf(organizationId), JournalEntryStatus.SUMMARY_FAILED);
        if (entries.isEmpty()) {
            return List.of();
        }
        Set<Long> granted = consentStateReader.grantedChildIds(
                entries.stream().map(JournalEntry::getChildId).collect(Collectors.toSet()), organizationId);
        Map<GroupKey, List<JournalEntry>> groups = entries.stream()
                .filter(e -> granted.contains(e.getChildId()))
                .collect(Collectors.groupingBy(e -> new GroupKey(e.getChildId(), e.getEntryDate()),
                        LinkedHashMap::new, Collectors.toList()));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, String> childNames = childRepository.findAllById(granted).stream()
                .collect(Collectors.toMap(Child::getId, Child::getName));
        return groups.entrySet().stream()
                .map(group -> new FailedSummaryGroupResponse(
                        group.getKey().childId(),
                        childNames.get(group.getKey().childId()),
                        group.getKey().entryDate(),
                        group.getValue().stream()
                                .map(e -> new FailedSummaryGroupResponse.Entry(e.getId(), e.getContent()))
                                .toList(),
                        REASON_NO_GROUNDED_CLAIMS))
                .toList();
    }

    /**
     * 그 묶음의 요약 실패 일지를 요약 전으로 돌린다. 워커가 다음 차례에 다시 집는다 — 같은 묶음에 다른 요약 대기 일지가
     * 있으면 함께, 승인 전 판이 있으면 그 판과 함께 다시 요약한다. 횟수 제한은 없다.
     *
     * <p>자동 요약이 꺼져 있으면 [요약] 요청이 있어야 요약하므로 요청도 남긴다. 아이 행 잠금으로 [다시 요약]끼리는 줄을
     * 서지만 O-32 [요약]({@link SummaryRunService#requestRun})은 아이 행을 잠그지 않아, 같은 묶음 첫 요청이 동시에 들어오면
     * 늦은 쪽이 요청 행 UNIQUE 에 걸려 이 처리 전체가 롤백된다(500, 다시 누르면 된다). 꺼짐은 장애 때만 쓰는 모드라 두었다.
     */
    @Transactional
    public void retry(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<JournalEntry> failed = lockFailedGroup(organizationId, childId, entryDate);
        failed.forEach(JournalEntry::retrySummary);
        if (!properties.autoRun()) {
            runRequests.request(new SummaryGroup(childId, entryDate, organizationId), LocalDateTime.now(clock));
        }
        log.info("failed summary group sent back to summary wait childId={} entryDate={} institutionId={} "
                + "journalEntryIds={}", childId, entryDate, organizationId, idsOf(failed));
    }

    /**
     * 그 묶음의 요약 실패 일지를 요약 없이 끝낸다. 요약할 내용 없음 확인(#244)과 같은 끝 상태(일지 {@code COMPLETED})이고,
     * 요약 행이 없으니 아동 맥락·보호자·인사이트로 가지 않는다. 누가 끝냈는지는 로그로만 남는다.
     */
    @Transactional
    public void close(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        List<JournalEntry> failed = lockFailedGroup(organizationId, childId, entryDate);
        failed.forEach(JournalEntry::closeFailedSummary);
        log.info("failed summary group closed without summary childId={} entryDate={} institutionId={} userId={} "
                + "journalEntryIds={}", childId, entryDate, organizationId, userId, idsOf(failed));
    }

    /**
     * 아이 행을 잠근 뒤 동의를 보고 그 묶음의 요약 실패 일지를 읽는다. 동의 철회·같은 묶음의 다른 처리와 줄을 선다 —
     * Gate 1 결정과 같은 잠금 순서다 ({@code SummaryGate1Service#requireConsented}). 동의가 꺼진 아이는 목록에서 빠지므로
     * 다른 기관 아이처럼 없는 것으로 응답한다.
     */
    private List<JournalEntry> lockFailedGroup(Long organizationId, Long childId, LocalDate entryDate) {
        childRepository.findByIdForUpdate(childId);
        if (consentStateReader.stateOf(childId, organizationId) != ConsentState.GRANTED) {
            throw new SummaryChildNotFoundException(childId);
        }
        List<Long> ids = journalEntryRepository.findGroupEntryIds(JournalEntryStatus.SUMMARY_FAILED, childId,
                entryDate, String.valueOf(organizationId));
        if (ids.isEmpty()) {
            throw new SummaryException(SummaryErrorCode.FAILED_NOT_FOUND,
                    "childId=" + childId + " entryDate=" + entryDate);
        }
        return journalEntryRepository.findAllById(ids);
    }

    private static List<Long> idsOf(List<JournalEntry> entries) {
        return entries.stream().map(JournalEntry::getId).sorted().toList();
    }
}
