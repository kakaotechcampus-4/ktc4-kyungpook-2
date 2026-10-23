package com.itda.backend.service.summary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
        findForUpdate(group).ifPresentOrElse(
                existing -> existing.requestAgain(requestedAt),
                () -> repository.saveAndFlush(SummaryRunRequest.of(
                        group.childId(), group.entryDate(), group.institutionId(), requestedAt)));
    }

    @Transactional(readOnly = true)
    public boolean isRequested(SummaryGroup group) {
        return repository.existsPending(group.childId(), group.entryDate(), group.institutionId());
    }

    @Transactional(readOnly = true)
    public List<SummaryGroup> requestedGroups() {
        return repository.findPending().stream()
                .map(r -> new SummaryGroup(r.getChildId(), r.getEntryDate(), r.getInstitutionId()))
                .toList();
    }

    /** 요약 결과를 남길 때 부른다. 집은 뒤에 다시 누른 요청({@code claimedAt} 이후)은 처리하지 않은 채로 남는다. */
    @Transactional
    public void markHandled(SummaryGroup group, LocalDateTime claimedAt) {
        findForUpdate(group).ifPresent(request -> request.markHandled(claimedAt));
    }

    /** 요약할 일지가 없게 된 요청을 거둔다. */
    @Transactional
    public void cancel(SummaryGroup group) {
        findForUpdate(group).ifPresent(SummaryRunRequest::cancel);
    }

    private Optional<SummaryRunRequest> findForUpdate(SummaryGroup group) {
        return repository.findGroupForUpdate(group.childId(), group.entryDate(), group.institutionId());
    }
}
