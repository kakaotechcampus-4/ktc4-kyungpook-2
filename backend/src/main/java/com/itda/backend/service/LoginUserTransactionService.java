package com.itda.backend.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.User;
import com.itda.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/** 로그인 저장 작업. 제약 위반 후 조회가 실패한 트랜잭션을 재사용하지 않도록 분리한다. */
@Service
@RequiredArgsConstructor
public class LoginUserTransactionService {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User findOrCreate(String kakaoId, String nickname) {
        return findAndRefresh(kakaoId, nickname)
                .orElseGet(() -> userRepository.saveAndFlush(User.pending(kakaoId, nickname)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<User> findExisting(String kakaoId, String nickname) {
        return findAndRefresh(kakaoId, nickname);
    }

    private Optional<User> findAndRefresh(String kakaoId, String nickname) {
        return userRepository.findByKakaoIdForUpdate(kakaoId).map(user -> {
            if (user.isDeleted()) {
                user.restore();
            }
            user.updateName(nickname);
            return user;
        });
    }
}
