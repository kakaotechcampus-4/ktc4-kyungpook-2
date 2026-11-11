package com.itda.backend.repository.sharing;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;

public interface SharingHistoryRepository extends JpaRepository<SharingHistory, Long> {

    List<SharingHistory> findByInsightResultIdIn(Collection<Long> insightResultIds);

    List<SharingHistory> findByInsightResultIdInAndReceiverTypeAndRevokedAtIsNull(
            Collection<Long> insightResultIds, ReceiverType receiverType);

    List<SharingHistory> findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
            Long childId, ReceiverType receiverType, Long receiverId);

    @Query("select distinct s.childId from SharingHistory s where s.childId in :childIds "
            + "and s.receiverType = :receiverType and s.receiverId = :receiverId "
            + "and s.revokedAt is null and s.readAt is null")
    List<Long> findChildIdsWithUnread(@Param("childIds") Collection<Long> childIds,
            @Param("receiverType") ReceiverType receiverType, @Param("receiverId") Long receiverId);

    List<SharingHistory> findByReceiverTypeAndReceiverIdAndRevokedAtIsNullOrderByIdDesc(
            ReceiverType receiverType, Long receiverId);

    Optional<SharingHistory> findByInsightResultIdAndReceiverTypeAndReceiverId(
            Long insightResultId, ReceiverType receiverType, Long receiverId);

    List<SharingHistory> findByChildIdAndReceiverType(Long childId, ReceiverType receiverType);

    List<SharingHistory> findByInsightResultIdInAndReceiverType(
            Collection<Long> insightResultIds, ReceiverType receiverType);

    @Query("select s.childId from SharingHistory s where s.insightResultId = :insightResultId "
            + "and s.receiverType = :receiverType and s.receiverId = :receiverId")
    Optional<Long> findChildIdOf(@Param("insightResultId") Long insightResultId,
            @Param("receiverType") ReceiverType receiverType, @Param("receiverId") Long receiverId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SharingHistory s set s.readAt = :at "
            + "where s.id = :id and s.readAt is null and s.revokedAt is null")
    int markReadIfUnread(@Param("id") Long id, @Param("at") LocalDateTime at);
}
