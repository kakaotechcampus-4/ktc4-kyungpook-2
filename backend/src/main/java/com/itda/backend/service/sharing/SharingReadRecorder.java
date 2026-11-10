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

@Component
@RequiredArgsConstructor
public class SharingReadRecorder {

    private final SharingHistoryRepository sharingHistoryRepository;
    private final ChildRepository childRepository;
    private final Clock clock;

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

    @Transactional(propagation = Propagation.MANDATORY)
    public SharingHistory markRead(SharingHistory lockedGrant) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        sharingHistoryRepository.markReadIfUnread(lockedGrant.getId(), now);
        return sharingHistoryRepository.findById(lockedGrant.getId())
                .orElseThrow(() -> notFound(lockedGrant.getInsightResultId()));
    }

    private static SharingException notFound(Long insightId) {
        return new SharingException(SharingErrorCode.NOT_FOUND, insightId);
    }
}
