package com.itda.backend.repository.user;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.user.KakaoUnlinkWebhook;

public interface KakaoUnlinkWebhookRepository extends JpaRepository<KakaoUnlinkWebhook, Long> {

    @Query("select w.id from KakaoUnlinkWebhook w order by w.id asc")
    List<Long> findPendingIds(Pageable page);

    @Modifying
    @Query("delete from KakaoUnlinkWebhook w where w.id = :id")
    int deleteOne(@Param("id") Long id);
}
