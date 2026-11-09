package com.itda.backend.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.CreatedAtEntity;

@Entity
@Table(
        name = "revoked_session",
        uniqueConstraints = @UniqueConstraint(
                name = RevokedSession.UK_REVOKED_SESSION,
                columnNames = "session_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedSession extends CreatedAtEntity {

    public static final String UK_REVOKED_SESSION = "uk_revoked_session_session_id";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

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
}
