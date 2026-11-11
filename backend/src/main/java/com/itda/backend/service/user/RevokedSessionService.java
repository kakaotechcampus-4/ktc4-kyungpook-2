package com.itda.backend.service.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.user.RevokedSessionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RevokedSessionService {

    private final RevokedSessionTransactionService revokedSessionTransactionService;
    private final RevokedSessionRepository revokedSessionRepository;

    public void revoke(String sessionId, Long userId) {
        try {
            revokedSessionTransactionService.record(sessionId, userId);
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.isViolationOf(e, RevokedSession.UK_REVOKED_SESSION)) {
                throw e;
            }
        }
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String sessionId) {
        return revokedSessionRepository.existsBySessionId(sessionId);
    }
}
