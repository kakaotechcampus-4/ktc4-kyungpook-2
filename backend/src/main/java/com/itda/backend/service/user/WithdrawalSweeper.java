package com.itda.backend.service.user;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 진행 중인 탈퇴를 주기적으로 다시 실행기에 세운다. 수신함에 남은 연결 해제 웹훅도 옮긴다. 카카오 결과를 몰랐거나, 이전 호출이 있는데 카카오가 거절했거나, DB 정리가
 * 실패했거나, 앱이 처리 중에 내려간 탈퇴를 끝낸다. 실제 일은 실행기 스레드가 한다 — 여기서 카카오를 부르지 않는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.withdrawal.worker", name = "enabled", havingValue = "true")
public class WithdrawalSweeper {

    private final WithdrawalService withdrawalService;
    private final KakaoUnlinkInbox kakaoUnlinkInbox;

    @Scheduled(fixedDelayString = "${app.withdrawal.worker.delay-ms}")
    public void run() {
        // 수신함에 남은(옮기지 못한) 연결 해제 웹훅은 수신함 스레드에 맡긴다 — 여기서 옮기면 한 회원 행의 잠금이 아래 탈퇴 재시도를 늦춘다
        kakaoUnlinkInbox.drainLater();
        withdrawalService.resumeAll();
    }
}
