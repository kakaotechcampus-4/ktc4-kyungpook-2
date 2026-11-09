package com.itda.backend.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.user.RevokedSession;

public interface RevokedSessionRepository extends JpaRepository<RevokedSession, Long> {

    boolean existsBySessionId(String sessionId);
}
