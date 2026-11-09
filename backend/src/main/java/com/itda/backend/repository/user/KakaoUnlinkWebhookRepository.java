package com.itda.backend.repository.user;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.user.KakaoUnlinkWebhook;

public interface KakaoUnlinkWebhookRepository extends JpaRepository<KakaoUnlinkWebhook, Long> {

    /** 아직 회원 행으로 옮기지 않은 웹훅. 먼저 받은 것부터. */
    @Query("select w.id from KakaoUnlinkWebhook w order by w.id asc")
    List<Long> findPendingIds(Pageable page);

    /** 옮긴 줄을 지운다. 0 이면 다른 실행이 먼저 옮겼다. */
    @Modifying
    @Query("delete from KakaoUnlinkWebhook w where w.id = :id")
    int deleteOne(@Param("id") Long id);
}
