package com.itda.backend.repository.sharing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;

public interface SharingHistoryRepository extends JpaRepository<SharingHistory, Long> {

    /** 그 인사이트를 지금 볼 수 있는 수신자들. 승인이 실제로 권한을 만들었는지 확인하는 데 쓴다. */
    List<SharingHistory> findByInsightResultId(Long insightResultId);

    boolean existsByInsightResultIdAndReceiverTypeAndReceiverId(
            Long insightResultId, ReceiverType receiverType, Long receiverId);
}
