package com.itda.backend.domain.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 받은 카카오 연결 해제 웹훅 한 줄 (수신함, DB 스키마 §4.5). 카카오는 3초 안에 답해야 하고 다시 보내 주지 않는다 — 웹훅 요청은
 * 회원 행을 잠그지 않고 이 줄만 남긴 뒤 답한다. 회원 행으로 옮기는 일은 따로 한다({@code WithdrawalSteps#absorbWebhook}).
 *
 * <p>옮기면 지운다. 남아 있는 줄은 아직 옮기지 못한 웹훅이다.
 */
@Entity
@Table(name = "kakao_unlink_webhook")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoUnlinkWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 웹훅이 알린 카카오 회원번호. {@code users.kakao_id} 와 같은 형식이다. */
    @Column(name = "kakao_id", nullable = false, length = 50)
    private String kakaoId;

    /** 웹훅을 받은 시각. 회원 행의 알림 시각이 된다 — 연결 해제는 이보다 먼저 일어났다. */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private KakaoUnlinkWebhook(String kakaoId, LocalDateTime receivedAt) {
        this.kakaoId = kakaoId;
        this.receivedAt = receivedAt;
    }

    public static KakaoUnlinkWebhook received(String kakaoId, LocalDateTime receivedAt) {
        if (kakaoId == null || kakaoId.isBlank() || receivedAt == null) {
            throw new IllegalArgumentException("웹훅의 카카오 회원번호 · 받은 시각은 필수입니다.");
        }
        return new KakaoUnlinkWebhook(kakaoId, receivedAt);
    }
}
