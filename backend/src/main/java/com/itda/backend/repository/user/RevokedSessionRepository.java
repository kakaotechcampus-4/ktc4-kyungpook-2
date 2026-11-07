package com.itda.backend.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.user.RevokedSession;

public interface RevokedSessionRepository extends JpaRepository<RevokedSession, Long> {

    /** 로그인한 요청마다 부른다. UNIQUE 인덱스로 찾는다. */
    boolean existsBySessionId(String sessionId);
}
