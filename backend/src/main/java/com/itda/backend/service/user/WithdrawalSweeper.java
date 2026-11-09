package com.itda.backend.service.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.withdrawal.worker", name = "enabled", havingValue = "true")
public class WithdrawalSweeper {

    private final WithdrawalService withdrawalService;
    private final KakaoUnlinkInbox kakaoUnlinkInbox;

    @Scheduled(fixedDelayString = "${app.withdrawal.worker.delay-ms}")
    public void run() {
        kakaoUnlinkInbox.drainLater();
        withdrawalService.resumeAll();
    }
}
