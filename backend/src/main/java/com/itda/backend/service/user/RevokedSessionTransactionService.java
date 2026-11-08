package com.itda.backend.service.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.RevokedSession;
import com.itda.backend.repository.user.RevokedSessionRepository;

import lombok.RequiredArgsConstructor;

/**
 * 로그아웃 기록 저장. 같은 로그인의 로그아웃이 겹쳐 UNIQUE 위반이 나면 이 트랜잭션은 롤백된 채 끝나고, 부른 쪽이 그
 * 위반만 "이미 기록됨" 으로 넘긴다. 같은 트랜잭션 안에서 위반을 잡으면 트랜잭션이 롤백 전용으로 남아 커밋 때 실패한다
 * ({@link LoginUserTransactionService} 와 같은 이유).
 */
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
