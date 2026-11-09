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

@Entity
@Table(name = "kakao_unlink_webhook")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoUnlinkWebhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kakao_id", nullable = false, length = 50)
    private String kakaoId;

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
