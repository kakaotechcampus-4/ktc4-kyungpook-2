package com.itda.backend.service.sharing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.sharing.ReceiverType;
import com.itda.backend.domain.sharing.SharingHistory;
import com.itda.backend.exception.sharing.SharingErrorCode;
import com.itda.backend.exception.sharing.SharingException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.sharing.SharingHistoryRepository;

import lombok.RequiredArgsConstructor;

/**
 * 인사이트 단건을 열 때 권한을 잠그고 처음 열어 본 시각을 남긴다. 보호자 단건과 기관 수신함 단건이 함께 쓴다.
 *
 * <p><b>철회가 먼저 반영되면 404, 열람이 먼저면 200 과 열람 시각을 남긴 뒤 철회된다.</b> 둘 중 하나뿐이라 DB 와
 * 응답이 어긋나지 않는다. 권한을 바꾸는 쪽(동의 철회, 보호자 연결 해제, Gate 2 승인)이 모두 {@code child} 행을
 * FOR UPDATE 로 먼저 잠그므로, 열람은 같은 행을 FOR SHARE 로 잡아 그들과 줄을 선다 (DB 스키마 §3.3). 열람끼리는
 * {@code child} 잠금에서 막지 않지만, 같은 권한 행의 열람 시각 UPDATE 끼리는 행 잠금에서 기다린다.
 *
 * <p>두 메서드 모두 <b>부르는 쪽 트랜잭션에 합류해야 한다</b> ({@code MANDATORY}). 따로 커밋하면 잠금이 먼저 풀려,
 * 응답을 만드는 사이에 철회가 끼어든다.
 *
 * <p>순서: {@link #lockActive} → 부르는 쪽 추가 판정 → {@link #markRead}. 판정에 걸린 글에 열람 시각을 남기면
 * 못 보는 글을 봤다고 기록하게 된다.
 */
@Component
@RequiredArgsConstructor
public class SharingReadRecorder {

    private final SharingHistoryRepository sharingHistoryRepository;
    private final ChildRepository childRepository;
    private final Clock clock;

    /**
     * 아이 행을 공유 잠금으로 잡고, 그 뒤에 처음으로 권한 행을 읽는다. 없거나 거둔 권한이면 404 다 — 남의 글이
     * 있는지조차 알려주지 않는다 (deny-by-default).
     *
     * <p>잠글 아이를 알려고 권한 엔티티를 먼저 읽지 않는다. 미리 읽어 두면 잠근 뒤 다시 물어도 영속성 컨텍스트가
     * 잠그기 전 값을 돌려줘 그사이 커밋된 철회를 놓친다. 그래서 {@code child_id} 값 하나만 고른다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SharingHistory lockActive(Long insightId, ReceiverType receiverType, Long receiverId) {
        Long childId = sharingHistoryRepository.findChildIdOf(insightId, receiverType, receiverId)
                .orElseThrow(() -> notFound(insightId));
        childRepository.findByIdForShare(childId).orElseThrow(() -> notFound(insightId));
        return sharingHistoryRepository
                .findByInsightResultIdAndReceiverTypeAndReceiverId(insightId, receiverType, receiverId)
                .filter(SharingHistory::isActive)
                .orElseThrow(() -> notFound(insightId));
    }

    /**
     * 처음 열어 본 시각을 남기고 <b>다시 읽은</b> 권한 행을 돌려준다. 응답은 반드시 돌려받은 것으로 만든다 —
     * 조건부 UPDATE 는 이미 들고 있는 객체의 값을 바꾸지 않아서, 넘긴 객체의 {@code readAt} 은 낡은 값이다.
     *
     * <p>UPDATE 가 0행이어도 정상이다. 같은 글을 동시에 연 다른 요청이 먼저 찍었으면 0행이다. 권한은 잠근 뒤에
     * 이미 확인했으므로 404 로 바꾸지 않고, 다시 읽은 처음 시각을 그대로 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SharingHistory markRead(SharingHistory lockedGrant) {
        // DB 는 마이크로초까지만 저장한다. 맞춰 두지 않으면 리눅스에서 응답과 저장값이 어긋난다.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        sharingHistoryRepository.markReadIfUnread(lockedGrant.getId(), now);
        return sharingHistoryRepository.findById(lockedGrant.getId())
                .orElseThrow(() -> notFound(lockedGrant.getInsightResultId()));
    }

    private static SharingException notFound(Long insightId) {
        return new SharingException(SharingErrorCode.NOT_FOUND, insightId);
    }
}
