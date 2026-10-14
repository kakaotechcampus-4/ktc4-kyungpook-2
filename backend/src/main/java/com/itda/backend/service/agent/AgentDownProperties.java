package com.itda.backend.service.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.ai.agent-down.*} 설정. 매칭·검증 워커가 AI 다운({@code AiAgentUnavailableException})을 만났을 때 쓴다.
 *
 * <p>AI 다운은 일지 잘못이 아니라서 처리 중이던 일지를 실패로 남기지 않고 대기로 되돌린다. 다만 BE 는
 * "AI 가 죽었다" 와 "AI 가 이 일지만 계속 거절한다" 를 구별할 수 없어서(검증 AI 는 Luna 실패를 이유와
 * 상관없이 503 으로 준다), 횟수 제한 없이 되돌리면 그런 일지가 대기열 맨 앞을 영영 막는다. 그래서 상한을 둔다 (#153).
 *
 * @param maxReleases AI 다운으로 되돌리는 최대 횟수. 이만큼 되돌린 뒤 또 다운이면 그 일지는 실패로 남긴다
 * @param pause       AI 다운 뒤 그 워커가 일지를 집지 않고 쉬는 시간. 쉬지 않으면 다음 차례(5초 뒤)에 바로 다시 불러서
 *                    1분도 안 되는 짧은 장애에도 상한을 채운다
 */
@ConfigurationProperties(prefix = "app.ai.agent-down")
public record AgentDownProperties(int maxReleases, Duration pause) {
}
