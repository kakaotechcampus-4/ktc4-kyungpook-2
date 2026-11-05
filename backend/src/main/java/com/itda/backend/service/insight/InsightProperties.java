package com.itda.backend.service.insight;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/**
 * {@code app.insight.*} 설정. 접속 주소·타임아웃·재시도는 공통 {@code app.ai.*} 를 그대로 쓴다 —
 * 인사이트는 LLM 을 60초까지 기다려 매칭·검증과 같아서 요약처럼 전용 RestClient 가 필요 없다.
 *
 * @param periodDays 한 번에 볼 기간의 길이(일). 기준 문서가 "여러 날·여러 기관을 겹쳐 본다" 고만 하고
 *                   길이를 정하지 않아서 설정으로 둔다 ({@code AI/insight/CRITERIA.md})
 * @param zone       기간의 끝을 "어제" 로 잡을 때 쓰는 시간대. 요약과 같은 값이어야 날짜가 어긋나지 않는다
 * @param lookbackDays 최근 며칠 안에 Gate 1 을 통과한 요약까지 다시 볼지 (#239). 승인이 늦어도 그 주를 보게 한다
 * @param includeCurrentWeek 끝나지 않은 이번 주도 볼지. <b>시연용</b>이다 — 켜면 이번 주는 승인이 들어올 때마다
 *                           새 회차가 생긴다. 기본은 끔
 * @param failureBackoffs 같은 아이·같은 기간이 연달아 실패했을 때 다시 집기까지 기다리는 시간 (#266). n 번째 연속
 *                        실패 뒤에는 n 번째 값을, 넘치면 마지막 값을 쓴다. 비우면 기다리지 않는다
 */
@ConfigurationProperties(prefix = "app.insight")
public record InsightProperties(WorkerProperties worker, int periodDays, ZoneId zone, int lookbackDays,
        boolean includeCurrentWeek, List<Duration> failureBackoffs) {

    /**
     * 관찰 날짜가 속한 기간의 끝. <b>일요일</b>로 고정한다 — "어제까지 7일" 로 잡으면 자정마다 키가 밀려 같은 아이가
     * 겹치는 창으로 매일 다시 돈다 ({@code InsightWorker}). 워커와 진행 현황(O-43)이 같은 계산을 쓴다.
     */
    public LocalDate periodEndOf(LocalDate entryDate) {
        return entryDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    public LocalDate periodStartOf(LocalDate periodEnd) {
        return periodEnd.minusDays(periodDays - 1L);
    }

    /** 오늘 분석할 수 있는 마지막 기간의 끝. 끝나지 않은 이번 주는 {@code includeCurrentWeek} 일 때만 넣는다. */
    public LocalDate lastPeriodEnd(LocalDate today) {
        LocalDate thisSunday = periodEndOf(today);
        return includeCurrentWeek ? thisSunday : thisSunday.minusWeeks(1);
    }
}
