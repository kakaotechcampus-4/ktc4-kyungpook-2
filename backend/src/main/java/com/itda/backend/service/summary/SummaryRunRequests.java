package com.itda.backend.service.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.repository.summary.SummaryRunRequestRepository;

import lombok.RequiredArgsConstructor;

/**
 * 교사가 누른 [요약] 요청({@link SummaryRunRequest}). 요약은 아직 처리하지 않은 요청이 있는 묶음만 한다.
 *
 * <p>워커는 집을 때가 아니라 결과를 남길 때 처리 표시를 한다. 집을 때 하면 AI 장애로 묶음이 되돌아갔을 때 요청까지
 * 사라진다. 처리한 요청도 지우지 않는다 (DB 스키마 §3.2).
 */
@Component
@RequiredArgsConstructor
public class SummaryRunRequests {

    private final SummaryRunRequestRepository repository;

    /**
     * 요청을 남긴다. 이미 있으면 누른 시각만 바꾼다.
     *
     * <p>같은 묶음을 동시에 처음 눌러 둘 다 "없음" 을 보면 늦게 넣는 쪽이 UNIQUE({@link SummaryRunRequest#UK_GROUP})에
     * 걸린다. 그 처리는 부르는 쪽이 한다 ({@link SummaryRunService#requestRun}).
     */
    @Transactional
    public void request(SummaryGroup group, LocalDateTime requestedAt) {
        if (repository.existsByChildIdAndEntryDateAndInstitutionId(
                group.childId(), group.entryDate(), group.institutionId())) {
            repository.advanceRequestedAt(group.childId(), group.entryDate(), group.institutionId(), requestedAt);
            return;
        }
        repository.saveAndFlush(SummaryRunRequest.of(
                group.childId(), group.entryDate(), group.institutionId(), requestedAt));
    }

    @Transactional(readOnly = true)
    public boolean isRequested(SummaryGroup group) {
        return repository.existsPending(group.childId(), group.entryDate(), group.institutionId());
    }

    /** 아직 처리하지 않은 요청과 그 묶음을 마지막으로 누른 시각. */
    @Transactional(readOnly = true)
    public Map<SummaryGroup, LocalDateTime> pendingRequests() {
        return repository.findPending().stream()
                .collect(Collectors.toMap(SummaryRunRequests::groupOf, SummaryRunRequest::getRequestedAt));
    }

    /** 그 기관의 그 날짜들 중 아직 처리하지 않은 요청이 있는 묶음. */
    @Transactional(readOnly = true)
    public Set<SummaryGroup> pendingGroupsOf(Long institutionId, Collection<LocalDate> dates) {
        if (dates.isEmpty()) {
            return Set.of();
        }
        return repository.findPendingIn(institutionId, dates).stream()
                .map(SummaryRunRequests::groupOf)
                .collect(Collectors.toSet());
    }

    /**
     * {@code until} 까지 누른 요청을 처리한 것으로 표시한다. 그 뒤에 누른 요청은 처리하지 않은 채로 남는다.
     * 요약 결과를 남길 때는 집은 시각을, 요약할 일지가 없어 거둘 때는 읽어 둔 누른 시각을 넘긴다.
     */
    @Transactional
    public void markHandled(SummaryGroup group, LocalDateTime until) {
        repository.advanceHandledUntil(group.childId(), group.entryDate(), group.institutionId(), until);
    }

    private static SummaryGroup groupOf(SummaryRunRequest request) {
        return new SummaryGroup(request.getChildId(), request.getEntryDate(), request.getInstitutionId());
    }
}
