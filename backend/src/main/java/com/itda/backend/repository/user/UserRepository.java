package com.itda.backend.repository.user;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.user.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 카카오 회원번호로 찾는다. 탈퇴를 마친 행은 회원번호를 지웠으므로 걸리지 않는다. 옛 방식으로 탈퇴 표시만 된 행은
     * 걸리는데, 로그인이 그 행의 식별정보를 지우고 새 회원을 만든다 (LoginUserTransactionService).
     */
    Optional<User> findByKakaoId(String kakaoId);

    /** 로그인 갱신 · 회원가입 · 탈퇴가 서로의 변경을 덮어쓰지 않도록 잠근다. 탈퇴 표시된 행도 찾는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.kakaoId = :kakaoId")
    Optional<User> findByKakaoIdForUpdate(@Param("kakaoId") String kakaoId);

    /** 로그인·세션 조회용. 탈퇴한 사용자는 인정하지 않는다. */
    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 회원가입용. 행을 잠가 같은 회원의 가입 요청이 동시에 두 번 들어와도 하나씩 처리되게 한다 —
     * 잠그지 않으면 둘 다 "아직 가입 전" 으로 보고 기관을 두 개 만들 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);

    /**
     * 탈퇴 접수 · 정리용. 탈퇴한 행도 잠근다 — 이미 탈퇴했는지(401 ACCOUNT_WITHDRAWN)를 없는 회원과 나눠 답해야 한다.
     * 잠금 순서의 맨 앞이다 (DB 스키마 §3.3).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * 초대 수락용. 탈퇴 정리({@link #findByIdForUpdate})와 줄을 세우되 같은 회원의 수락끼리는 막지 않는다.
     * 탈퇴한 행도 찾아 이유를 나눠 답한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForShare(@Param("id") Long id);

    /** 탈퇴 결과를 볼 때 쓰는 두 칸. */
    interface WithdrawalView {
        java.time.LocalDateTime getDeletedAt();

        com.itda.backend.domain.user.WithdrawalStatus getWithdrawalStatus();
    }

    /**
     * 탈퇴 API 가 실행기를 기다린 뒤 결과를 읽는다. 엔티티가 아니라 값으로 읽는다 — 요청 동안 열려 있는 영속성 컨텍스트
     * (open-in-view)에 접수 때 읽은 회원이 남아 있어, 엔티티로 읽으면 실행기가 커밋한 결과 대신 그 옛 값을 돌려준다.
     */
    @Query("select u.deletedAt as deletedAt, u.withdrawalStatus as withdrawalStatus from User u where u.id = :id")
    Optional<WithdrawalView> findWithdrawalViewById(@Param("id") Long id);

    /** 탈퇴 실행기가 다시 볼 회원 — 진행 중인 탈퇴와 확인하지 않은 연결 해제 웹훅. 오래된 것부터. */
    @Query("select u.id from User u where u.deletedAt is null"
            + " and (u.withdrawalStatus is not null or u.kakaoUnlinkReportedAt is not null)"
            + " order by coalesce(u.withdrawalRequestedAt, u.kakaoUnlinkReportedAt) asc, u.id asc")
    List<Long> findWithdrawingIds();
}
