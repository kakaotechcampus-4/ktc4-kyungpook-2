package com.itda.backend.service.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 진행 중인 탈퇴를 주기적으로 다시 실행기에 세운다. 카카오 결과를 몰랐거나, 이전 호출이 있는데 카카오가 거절했거나, DB 정리가
 * 실패했거나, 앱이 처리 중에 내려간 탈퇴를 끝낸다. 실제 일은 실행기 스레드가 한다 — 여기서 카카오를 부르지 않는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.withdrawal.worker", name = "enabled", havingValue = "true")
public class WithdrawalSweeper {

    private final WithdrawalService withdrawalService;

    @Scheduled(fixedDelayString = "${app.withdrawal.worker.delay-ms}")
    public void run() {
        withdrawalService.resumeAll();
    }
}
