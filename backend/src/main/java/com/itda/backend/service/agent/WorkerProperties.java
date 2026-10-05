package com.itda.backend.service.agent;

/**
 * 에이전트 워커 하나의 설정 ({@code app.<단계>.worker.*}).
 *
 * @param enabled   꺼 두면 워커 빈 자체가 만들어지지 않는다 (테스트 프로필)
 * @param delayMs   한 번 처리를 마친 뒤 다음 처리까지 쉬는 시간
 * @param batchSize 한 번에 집어 가는 일지 수
 */
public record WorkerProperties(boolean enabled, long delayMs, int batchSize) {
}
