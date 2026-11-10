package com.itda.backend.repository.user;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.user.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByKakaoId(String kakaoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.kakaoId = :kakaoId")
    Optional<User> findByKakaoIdForUpdate(@Param("kakaoId") String kakaoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select u from User u where u.kakaoId = :kakaoId")
    Optional<User> findByKakaoIdForUpdateNoWait(@Param("kakaoId") String kakaoId);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForShare(@Param("id") Long id);

    interface WithdrawalView {
        java.time.LocalDateTime getDeletedAt();

        com.itda.backend.domain.user.WithdrawalStatus getWithdrawalStatus();
    }

    @Query("select u.deletedAt as deletedAt, u.withdrawalStatus as withdrawalStatus from User u where u.id = :id")
    Optional<WithdrawalView> findWithdrawalViewById(@Param("id") Long id);

    @Query("select u.id from User u where u.deletedAt is null"
            + " and (u.withdrawalStatus is not null or u.kakaoUnlinkReportedAt is not null)"
            + " order by coalesce(u.withdrawalRequestedAt, u.kakaoUnlinkReportedAt) asc, u.id asc")
    List<Long> findWithdrawingIds();
}
