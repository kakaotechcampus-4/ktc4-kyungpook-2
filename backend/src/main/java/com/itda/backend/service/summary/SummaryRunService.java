package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.Instant;
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
 * 그래서 업로드 직후(아직 매칭·검증 중)에 눌러도 받는다.
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
    private final SummaryProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void requestRun(String userId, Long childId, LocalDate entryDate) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new SummaryChildNotFoundException(childId);
        }
        String institutionId = String.valueOf(organizationId);
        boolean hasNewEntry = !journalEntryRepository.findGroupEntryIds(
                JournalEntryStatus.VALIDATED, childId, entryDate, institutionId).isEmpty();
        // 매칭 전 일지는 아동을 몰라 기관·날짜로 본다. 이 아이 일지일 수 있으니 끝나면 요약하도록 받아 둔다.
        boolean stillProcessing = journalEntryRepository.existsInProgress(
                SummaryService.IN_PROGRESS, institutionId, entryDate);
        if (!hasNewEntry && !stillProcessing) {
            throw new SummaryRunValidationException(
                    "no journal entry to summarize childId=" + childId + " entryDate=" + entryDate);
        }
        // 마감이 지나면 어차피 자동으로 요약되므로 그때까지만 유효하다. 이미 지난 날짜면 디바운스만큼만 둔다.
        Instant now = clock.instant();
        Instant cutoff = properties.cutoffOf(entryDate);
        Instant expiresAt = now.isBefore(cutoff) ? cutoff : now.plus(properties.debounce());
        runRequests.request(new SummaryGroup(childId, entryDate, organizationId), expiresAt);
    }
}
