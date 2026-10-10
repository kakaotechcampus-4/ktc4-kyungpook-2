package com.itda.backend.service.summary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/**
 * {@code app.summary.*} 설정. AI 서버 접속 설정은 {@code app.ai.*}({@link com.itda.backend.service.agent.AiAgentProperties}).
 *
 * <p>03:00 과 30분은 실제 업로드 분포를 보고 정한 값이 아니라서 숫자만 바꿀 수 있게 설정으로 둔다
 * (AI/summary/CRITERIA.md §5).
 *
 * @param cutoffTime entry_date 다음 날 이 시각이 지나야 그 날짜를 요약한다. 기관끼리 업로드 시각이 벌어지는 것을 덮는다
 * @param debounce   묶음의 마지막 일지가 들어온 뒤 이만큼 더 기다린다. 같은 사람이 연달아 올리는 간격을 덮는다
 * @param zone       마감 시각의 기준 시간대
 * @param readTimeout 요약 에이전트 응답 대기 시간. AI 가 LLM 을 90초까지 기다리므로 그보다 길어야 한다
 */
@ConfigurationProperties(prefix = "app.summary")
public record SummaryProperties(WorkerProperties worker, LocalTime cutoffTime, Duration debounce, ZoneId zone,
        Duration readTimeout) {

    /** 그 날짜의 마감 — entry_date 다음 날 {@code cutoffTime}. 이 시각부터 그 날짜를 요약할 수 있다. */
    public Instant cutoffOf(LocalDate entryDate) {
        return entryDate.plusDays(1).atTime(cutoffTime).atZone(zone).toInstant();
    }
}
