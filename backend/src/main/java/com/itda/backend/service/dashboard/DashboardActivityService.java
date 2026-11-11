package com.itda.backend.service.dashboard;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.matching.MatchingStatus;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.dashboard.AiActivityResponse;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.insight.InsightActivityReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DashboardActivityService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final Set<ValidationVerdict> JUDGED =
            EnumSet.of(ValidationVerdict.PASS, ValidationVerdict.REVIEW, ValidationVerdict.BLOCK);

    private final UserService userService;
    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ValidationResultRepository validationResultRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final InsightActivityReader insightActivityReader;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AiActivityResponse getActivity(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        String institutionId = String.valueOf(organizationId);
        LocalDateTime since = startOfToday();
        return new AiActivityResponse(
                journalEntryRepository.countCreatedSince(institutionId, since),
                matchingResultRepository.countConfirmedByAgentSince(institutionId, MatchingStatus.AUTO, since),
                validationResultRepository.countByVerdictSince(institutionId, JUDGED, since),
                summaryResultRepository.countGeneratedSince(organizationId, SummaryStatus.GATE1_PENDING, since),
                insightActivityReader.countCreatedSince(organizationId, since));
    }

    private LocalDateTime startOfToday() {
        return clock.instant().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
