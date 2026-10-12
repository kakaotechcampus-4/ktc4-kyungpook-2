package com.itda.backend.service.validation;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.MatchingResult;
import com.itda.backend.dto.request.ValidationAgentRequest;
import com.itda.backend.exception.ValidationTargetException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.MatchingResultRepository;

import lombok.RequiredArgsConstructor;

/** 검증 워커가 쓰는 일지 집기·요청 조립. AI 호출은 트랜잭션 밖에서 하도록 단계마다 트랜잭션을 나눈다. */
@Service
@RequiredArgsConstructor
public class ValidationService {

    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ChildRepository childRepository;

    /**
     * 매칭이 확정된 일지를 집어 가며 VALIDATING 으로 바꾼다. AI 자동 확정과 선생님이 확인 큐에서 확정한 일지가
     * 둘 다 MATCHED 로 들어온다. 커밋되면 다음 실행이 같은 일지를 다시 집지 않는다.
     */
    @Transactional
    public List<Long> claimMatched(int batchSize) {
        List<JournalEntry> targets = journalEntryRepository.findValidationTargets(
                JournalEntryStatus.MATCHED, Limit.of(batchSize));
        targets.forEach(JournalEntry::startValidating);
        return targets.stream().map(JournalEntry::getId).toList();
    }

    /** AI 입력은 저장하지 않고 그때그때 조립한다 (DB 스키마 §2.2). */
    @Transactional(readOnly = true)
    public ValidationTarget prepareRequest(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId)
                .orElseThrow(() -> new ValidationTargetException("journal entry not found: " + journalEntryId));
        Long childId = entry.getChildId();
        if (childId == null) {
            throw new ValidationTargetException("journal entry has no matched child: " + journalEntryId);
        }
        // 매칭 뒤에 아동이 삭제 표시돼도 이 일지의 주인공은 그대로라 이름을 싣는다.
        String childName = childRepository.findById(childId).map(Child::getName).orElse(null);
        Long matchingResultId = matchingResultRepository.findFirstByJournalEntryIdOrderByIdDesc(journalEntryId)
                .map(MatchingResult::getId)
                .orElse(null);

        return new ValidationTarget(
                new ValidationAgentRequest(entry.getId(), entry.getContent(), childId, childName),
                matchingResultId);
    }

    /** 집어 갔지만 처리하지 않은 일지를 검증 대기로 돌려놓는다. 이미 다른 상태로 바뀐 일지는 건드리지 않는다. */
    @Transactional
    public void release(List<Long> journalEntryIds) {
        journalEntryRepository.findAllById(journalEntryIds).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.VALIDATING)
                .forEach(JournalEntry::releaseValidating);
    }

    /** 이전에 꺼진 앱이 처리하다 만 일지를 검증 대기로 되돌린다. 워커가 첫 실행에서 한 번만 부른다. */
    @Transactional
    public int releaseStuck() {
        List<JournalEntry> stuck = journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.VALIDATING);
        stuck.forEach(JournalEntry::releaseValidating);
        return stuck.size();
    }
}
