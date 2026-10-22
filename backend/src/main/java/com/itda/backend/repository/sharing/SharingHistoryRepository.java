package com.itda.backend.repository.sharing;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;

public interface SharingHistoryRepository extends JpaRepository<SharingHistory, Long> {

    /** 그 인사이트에 한 번이라도 생긴 권한. 거둔 것도 함께 온다 — 감사 이력이라 지우지 않는다. */
    List<SharingHistory> findByInsightResultId(Long insightResultId);

    /** 아직 살아 있는 기관 권한. 재료 기관의 동의가 꺼질 때 거둘 대상이다 (#213). */
    List<SharingHistory> findByInsightResultIdInAndReceiverTypeAndRevokedAtIsNull(
            Collection<Long> insightResultIds, ReceiverType receiverType);

    /** 그 수신자가 이 아이에 대해 아직 가지고 있는 권한. 보호자 연결이 끊길 때 쓴다. */
    List<SharingHistory> findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
            Long childId, ReceiverType receiverType, Long receiverId);
}
