package com.itda.backend.domain.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * "이 로그인은 로그아웃했다" 는 기록 한 줄 (DB 스키마 §4.4). 이 로그인 번호를 가진 출입증은 더 받지 않는다.
 *
 * <p>출입증은 쓰는 동안 자동으로 연장된다. 로그아웃 직전에 나간 요청의 응답이 로그아웃보다 늦게 도착하면, 그 응답에 실린
 * 연장 쿠키가 지운 로그인을 되살린다. 쿠키는 탭끼리 같이 쓰므로 다른 탭의 요청도 그렇다. 쿠키 순서로는 막을 수 없어,
 * 서버가 로그인 번호로 거절한다. 덤으로 로그아웃 뒤 새어 나간 출입증도 쓸 수 없다.
 *
 * <p><b>회원이 아니라 로그인 단위다.</b> 기관은 1기관 1계정이라 여러 선생님이 같은 계정으로 동시에 쓴다. 회원 단위로 끊으면
 * 한 사람의 로그아웃이 다른 선생님까지 끊는다.
 *
 * <p><b>쌓기만 한다.</b> 수정 · 삭제 메서드를 두지 않는다. 한 줄 = 로그아웃 한 번이라 작다. 최대 로그인 시간(12시간)이
 * 지난 줄은 그 번호의 출입증이 이미 죽어 쓰이지 않는다.
 */
@Entity
@Table(
        name = "revoked_session",
        uniqueConstraints = @UniqueConstraint(
                name = RevokedSession.UK_REVOKED_SESSION,
                columnNames = "session_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedSession {

    /** 같은 로그인을 두 번 로그아웃해도 줄은 하나다. */
    public static final String UK_REVOKED_SESSION = "uk_revoked_session_session_id";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 출입증의 로그인 번호({@code sid}). UUID 문자열이다. */
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    /** 로그아웃한 회원. 다른 테이블처럼 FK 제약 없이 컬럼만 둔다. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 로그아웃한 시각. */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    private RevokedSession(String sessionId, Long userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public static RevokedSession of(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            throw new IllegalArgumentException("로그아웃 기록의 로그인 번호·회원은 필수입니다.");
        }
        return new RevokedSession(sessionId, userId);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
