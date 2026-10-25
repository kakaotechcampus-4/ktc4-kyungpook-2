package com.itda.backend.service.summary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryRunRequest;
import com.itda.backend.exception.summary.SummaryChildNotFoundException;
import com.itda.backend.exception.summary.SummaryRunValidationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 교사의 [요약]. 그 묶음을 요약하도록 요청을 DB 에 남긴다 — 요약은 이 요청이 있는 묶음만 한다 (#181).
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
    private final Clock clock;

    /**
     * 트랜잭션을 걸지 않는다. 같은 묶음을 동시에 눌러 늦게 넣은 쪽이 요청 행 UNIQUE 에 걸리면 그 저장은 롤백되는데,
     * 바깥 트랜잭션이 있으면 그것까지 롤백으로 표시돼 다시 시도할 수 없다. 다시 시도하면 먼저 들어간 행을 찾아
     * 누른 시각만 바꾼다.
     */
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
        SummaryGroup group = new SummaryGroup(childId, entryDate, organizationId);
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            runRequests.request(group, now);
        } catch (DataIntegrityViolationException e) {
            if (!isViolationOf(e, SummaryRunRequest.UK_GROUP)) {
                throw e;
            }
            runRequests.request(group, now);
        }
    }

    private static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
