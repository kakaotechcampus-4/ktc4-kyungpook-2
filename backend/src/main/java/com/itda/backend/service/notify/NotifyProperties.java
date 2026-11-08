package com.itda.backend.service.notify;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.itda.backend.service.agent.WorkerProperties;

/**
 * {@code app.notify.*} 설정 (#419). 접속 주소·연결 대기는 공통 {@code app.ai.*} 를 쓴다.
 *
 * @param failureBackoffs 같은 아이·같은 기간의 발송이 연달아 실패했을 때 다시 집기까지 기다리는 시간.
 *                        n 번째 연속 실패 뒤에는 n 번째 값을, 넘치면 마지막 값을 쓴다. 인사이트 워커와 같은
 *                        장치다 (#266) — 기다리지 않으면 계속 실패하는 아이가 맨 앞에 와서 뒤를 막는다
 * @param readTimeout     알림 에이전트 응답 대기. 모델 호출 한 번 + SMTP 왕복이라 인사이트보다 짧다
 * @param timeoutRetries  응답 대기 시간 초과 뒤 다시 보내는 횟수.
 *                        <b>0 이다</b> — 시간 초과로 다시 보내도 AI 는 앞 요청을 멈추지 않는다. 앞 요청이
 *                        메일을 이미 보냈을 수 있다. 중복 발송은 AI 쪽 {@code notification_id} 가 막지만,
 *                        막을 수 있다고 해서 일부러 두 번 보낼 이유는 없다
 */
@ConfigurationProperties(prefix = "app.notify")
public record NotifyProperties(WorkerProperties worker, List<Duration> failureBackoffs,
        Duration readTimeout, int timeoutRetries) {
}
