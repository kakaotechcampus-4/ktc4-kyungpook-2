package com.itda.backend.service.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.repository.user.RevokedSessionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RevokedSessionTransactionService {

    private final RevokedSessionRepository revokedSessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String sessionId, Long userId) {
        if (revokedSessionRepository.existsBySessionId(sessionId)) {
            return;
        }
        revokedSessionRepository.saveAndFlush(RevokedSession.of(sessionId, userId));
    }
}
