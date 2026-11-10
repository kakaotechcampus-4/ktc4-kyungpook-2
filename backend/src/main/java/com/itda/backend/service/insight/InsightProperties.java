package com.itda.backend.service.insight;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

@ConfigurationProperties(prefix = "app.insight")
public record InsightProperties(WorkerProperties worker, int periodDays, ZoneId zone, int lookbackDays,
        boolean includeCurrentWeek, List<Duration> failureBackoffs, Duration readTimeout, int timeoutRetries) {

    public LocalDate periodEndOf(LocalDate entryDate) {
        return entryDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    public LocalDate periodStartOf(LocalDate periodEnd) {
        return periodEnd.minusDays(periodDays - 1L);
    }

    public LocalDate lastPeriodEnd(LocalDate today) {
        LocalDate thisSunday = periodEndOf(today);
        return includeCurrentWeek ? thisSunday : thisSunday.minusWeeks(1);
    }
}
