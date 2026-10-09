package com.itda.backend.service.summary;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.exception.SummaryChildNotFoundException;
import com.itda.backend.exception.SummaryRunValidationException;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.service.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 교사의 "지금 요약". 마감(다음 날 03:00)과 디바운스를 기다리지 않고 그 묶음을 요약하도록 요청해 둔다.
 * 실제 요약은 워커가 다음 차례에 한다 — 같은 기관·날짜에 매칭·검증 중인 일지가 있으면 그것이 끝난 뒤다.
 *
 * <p>묶음의 기관은 요청한 교사의 기관이다. 다른 기관이 쓴 일지를 대신 요약시키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SummaryRunService {

    private final UserService userService;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final SummaryRunRequests runRequests;

    @Transactional(readOnly = true)
    public void requestRun(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new SummaryChildNotFoundException(childId);
        }
        // 요약할 새 일지가 없는 요청을 받아 두면, 한참 뒤 그 날짜 일지가 올라왔을 때 디바운스 없이 요약돼 버린다.
        String institutionId = String.valueOf(organizationId);
        boolean hasNewEntry = !journalEntryRepository.findGroupEntryIds(
                JournalEntryStatus.VALIDATED, childId, entryDate, institutionId).isEmpty();
        if (!hasNewEntry) {
            throw new SummaryRunValidationException(
                    "no validated journal entry to summarize childId=" + childId + " entryDate=" + entryDate);
        }
        runRequests.request(new SummaryGroup(childId, entryDate, organizationId));
    }
}
