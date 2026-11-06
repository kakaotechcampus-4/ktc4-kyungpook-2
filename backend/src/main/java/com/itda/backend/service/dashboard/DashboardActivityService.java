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

/**
 * 대시보드 "오늘 AI가 한 일" (#370, api-spec O-81). 서울 기준 오늘 0시부터 우리 기관 기록에 AI 가 한 일을 단계마다 센다.
 * 쿼리는 항목마다 하나라 기록 수와 무관하다.
 */
@Service
@RequiredArgsConstructor
public class DashboardActivityService {

    /** 선생님이 보는 "오늘"의 기준. 서버 시간대와 상관없이 서울 날짜로 자른다. */
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 검증 결과 중 AI 가 판정을 낸 것. FAILED 는 AI 호출 실패라 뺀다. */
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

    /**
     * 서울 오늘 0시를 JVM 시간대의 벽시계 시각으로. 행의 생성 시각은 엔티티가 {@code LocalDateTime.now()} 로 JVM 시간대에
     * 남기므로 같은 기준으로 맞춘다 (운영은 TZ=Asia/Seoul 이라 그대로 0시다).
     */
    private LocalDateTime startOfToday() {
        return clock.instant().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
