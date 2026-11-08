package com.itda.backend.service.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.user.RevokedSessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * 로그아웃한 로그인(출입증의 로그인 번호)을 기록하고, 요청마다 그 번호가 로그아웃했는지 본다. 왜 필요한지는
 * {@link RevokedSession}.
 */
@Service
@RequiredArgsConstructor
public class RevokedSessionService {

    private final RevokedSessionTransactionService revokedSessionTransactionService;
    private final RevokedSessionRepository revokedSessionRepository;

    /**
     * 이 로그인을 로그아웃한 것으로 남긴다. 이미 남아 있으면 그대로다 — 같은 로그인의 로그아웃이 겹쳐도 둘 다 성공한다.
     *
     * <p>트랜잭션을 걸지 않는다. 저장은 따로 연 트랜잭션에서 하고, 겹쳐서 난 UNIQUE 위반은 그 트랜잭션이 롤백된 뒤 여기서
     * 넘긴다. 다른 제약 위반은 숨기지 않는다.
     */
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
