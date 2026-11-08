package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.insight.InsightNotification;
import com.itda.backend.domain.insight.InsightNotificationStatus;

public interface InsightNotificationRepository extends JpaRepository<InsightNotification, Long> {

    /**
     * 그 아이·그 기간의 가장 최근 발송. 다음 판 번호의 기준이자 "이미 보냈나" 의 답이다.
     *
     * <p>{@code SENT} 면 건너뛴다. {@code BLOCKED} 도 건너뛴다 — 재료가 그대로면 다시 써도 같은 글이
     * 나온다. 재료가 늘었는지는 {@code lastInsightId} 로 본다.
     */
    Optional<InsightNotification> findFirstByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    /** 그 아이·그 기간의 발송 전부, 최근 판부터. 연속 실패 횟수를 센다. 한 기간에 몇 줄뿐이다. */
    List<InsightNotification> findByChildIdAndPeriodFromAndPeriodToOrderByRevisionDesc(
            Long childId, LocalDate periodFrom, LocalDate periodTo);

    /**
     * 앱이 죽어 {@code RUNNING} 으로 남은 발송을 찾는다. 집기는 {@code FAILED} 만 다시 가져가므로,
     * 되돌리지 않으면 그 아동·기간은 <b>영영 안 나간다</b>.
     *
     * <p>⚠️ <b>되돌릴 때 메일이 두 번 나갈 수 있다.</b> 메일을 보낸 직후 앱이 죽으면 RUNNING 으로 남는데,
     * BE 는 나갔는지 알 수 없다. 그래서 AI 쪽 MCP 서버가 {@code notification_id} 로 한 번 더 막는다 —
     * 같은 판 번호로 다시 오면 보내지 않고 처음 {@code message_id} 를 돌려준다 (AI/notify/CRITERIA.md §5).
     */
    List<InsightNotification> findByStatus(InsightNotificationStatus status);
}
