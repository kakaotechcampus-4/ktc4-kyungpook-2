package com.itda.backend.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 탈퇴한 사용자까지 찾는다. 재가입 시 새 행 대신 기존 행을 되살려야 하기 때문이다 —
     * kakao_id 에 유니크 제약이 있어서 탈퇴한 행이 남아 있으면 같은 번호로 INSERT 할 수 없다.
     */
    Optional<User> findByKakaoId(String kakaoId);

    /** 로그인·세션 조회용. 탈퇴한 사용자는 인정하지 않는다. */
    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 회원가입용. 행을 잠가 같은 회원의 가입 요청이 동시에 두 번 들어와도 하나씩 처리되게 한다 —
     * 잠그지 않으면 둘 다 "아직 가입 전" 으로 보고 기관을 두 개 만들 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);
}
