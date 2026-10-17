package com.itda.backend.service.insight;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/**
 * {@code app.insight.*} 설정. 접속 주소·타임아웃·재시도는 공통 {@code app.ai.*} 를 그대로 쓴다 —
 * 인사이트는 LLM 을 60초까지 기다려 매칭·검증과 같아서 요약처럼 전용 RestClient 가 필요 없다.
 *
 * @param periodDays 한 번에 볼 기간의 길이(일). 기준 문서가 "여러 날·여러 기관을 겹쳐 본다" 고만 하고
 *                   길이를 정하지 않아서 설정으로 둔다 ({@code AI/insight/CRITERIA.md})
 * @param zone       기간의 끝을 "어제" 로 잡을 때 쓰는 시간대. 요약과 같은 값이어야 날짜가 어긋나지 않는다
 */
@ConfigurationProperties(prefix = "app.insight")
public record InsightProperties(WorkerProperties worker, int periodDays, ZoneId zone) {
}
