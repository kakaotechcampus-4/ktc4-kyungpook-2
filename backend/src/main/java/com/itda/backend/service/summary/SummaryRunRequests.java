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

@Component
@RequiredArgsConstructor
public class SummaryRunRequests {

    private final SummaryRunRequestRepository repository;

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

    @Transactional(readOnly = true)
    public Map<SummaryGroup, LocalDateTime> pendingRequests() {
        return repository.findPending().stream()
                .collect(Collectors.toMap(SummaryRunRequests::groupOf, SummaryRunRequest::getRequestedAt));
    }

    @Transactional(readOnly = true)
    public Set<SummaryGroup> pendingGroupsOf(Long institutionId, Collection<LocalDate> dates) {
        if (dates.isEmpty()) {
            return Set.of();
        }
        return repository.findPendingIn(institutionId, dates).stream()
                .map(SummaryRunRequests::groupOf)
                .collect(Collectors.toSet());
    }

    @Transactional
    public void markHandled(SummaryGroup group, LocalDateTime until) {
        repository.advanceHandledUntil(group.childId(), group.entryDate(), group.institutionId(), until);
    }

    private static SummaryGroup groupOf(SummaryRunRequest request) {
        return new SummaryGroup(request.getChildId(), request.getEntryDate(), request.getInstitutionId());
    }
}
