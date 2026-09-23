package com.itda.backend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 탈퇴한 사용자까지 찾는다. 재가입 시 새 행 대신 기존 행을 되살려야 하기 때문이다 —
     * kakao_id 에 유니크 제약이 있어서 탈퇴한 행이 남아 있으면 같은 번호로 INSERT 할 수 없다.
     */
    Optional<User> findByKakaoId(String kakaoId);

    /** 로그인·세션 조회용. 탈퇴한 사용자는 인정하지 않는다. */
    Optional<User> findByIdAndDeletedAtIsNull(Long id);
}
