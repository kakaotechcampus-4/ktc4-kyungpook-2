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

    /** 그 인사이트에 한 번이라도 생긴 권한. 거둔 것도 함께 온다 — 감사 이력이라 지우지 않는다. */
    List<SharingHistory> findByInsightResultId(Long insightResultId);

    /** 아직 살아 있는 기관 권한. 재료 기관의 동의가 꺼질 때 거둘 대상이다 (#213). */
    List<SharingHistory> findByInsightResultIdInAndReceiverTypeAndRevokedAtIsNull(
            Collection<Long> insightResultIds, ReceiverType receiverType);

    /** 그 수신자가 이 아이에 대해 아직 가지고 있는 권한. 보호자 연결이 끊길 때 쓴다. */
    List<SharingHistory> findByChildIdAndReceiverTypeAndReceiverIdAndRevokedAtIsNull(
            Long childId, ReceiverType receiverType, Long receiverId);

    /**
     * 그 수신자가 아직 볼 수 있는 것 전부, 최근 열린 순. 보호자 인사이트 목록(#233)이 쓴다 —
     * 보호자는 자기에게 열린 글만 본다.
     */
    List<SharingHistory> findByReceiverTypeAndReceiverIdAndRevokedAtIsNullOrderByIdDesc(
            ReceiverType receiverType, Long receiverId);

    /**
     * 그 인사이트의 권한 중 한 줄. <b>거둔 행도 온다</b> — "지금 볼 수 있나" 는 부르는 쪽이
     * {@link SharingHistory#isActive()} 로 본다.
     */
    Optional<SharingHistory> findByInsightResultIdAndReceiverTypeAndReceiverId(
            Long insightResultId, ReceiverType receiverType, Long receiverId);

    /** 그 인사이트에 한 번이라도 생긴 기관 권한. 거둔 것도 온다 — 보호자 화면은 {@code isActive} 로 거른다. */
    List<SharingHistory> findByInsightResultIdAndReceiverType(
            Long insightResultId, ReceiverType receiverType);

    /**
     * 그 권한 줄의 아이만. 열람은 {@code child} 행을 먼저 잠그는데, 잠글 아이를 알려고 권한 엔티티를 미리 읽으면
     * 잠근 뒤 다시 물어도 영속성 컨텍스트가 옛 값을 돌려준다. 그래서 값 하나만 고른다 ({@code SharingReadRecorder}).
     */
    @Query("select s.childId from SharingHistory s where s.insightResultId = :insightResultId "
            + "and s.receiverType = :receiverType and s.receiverId = :receiverId")
    Optional<Long> findChildIdOf(@Param("insightResultId") Long insightResultId,
            @Param("receiverType") ReceiverType receiverType, @Param("receiverId") Long receiverId);

    /**
     * 처음 열어 본 시각을 남긴다. <b>처음 한 번만</b>, <b>거둔 권한에는 찍지 않는다.</b> 다시 열 때마다 덮으면
     * "처음 본 시각" 이 사라지고, 못 보는 글을 봤다고 남기면 감사 기록이 거짓이 된다 (§9.4).
     *
     * <p>엔티티를 고쳐 저장하지 않고 조건부 UPDATE 한 번으로 찍는다. 두 열람이 동시에 {@code read_at = NULL} 을
     * 읽고 각자 저장하면 나중 것이 처음 시각을 덮는다 — 여기서는 앞선 UPDATE 가 커밋되면 뒤의 UPDATE 가 조건을
     * 다시 보고 0행이 된다. 그래서 0행은 "이미 누가 찍었다" 일 수 있다. 부르는 쪽은 다시 읽은 값을 쓴다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SharingHistory s set s.readAt = :at "
            + "where s.id = :id and s.readAt is null and s.revokedAt is null")
    int markReadIfUnread(@Param("id") Long id, @Param("at") LocalDateTime at);
}
