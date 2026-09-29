package com.itda.backend.service.matching;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.dto.request.MatchingAgentRequest;
import com.itda.backend.exception.MatchingTargetException;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.RawRecordRepository;

import lombok.RequiredArgsConstructor;

/** 매칭 워커가 쓰는 일지 집기·요청 조립. AI 호출은 트랜잭션 밖에서 하도록 단계마다 트랜잭션을 나눈다. */
@Service
@RequiredArgsConstructor
public class MatchingService {

    private final JournalEntryRepository journalEntryRepository;
    private final RawRecordRepository rawRecordRepository;
    private final OrganizationRepository organizationRepository;
    private final ChildRepository childRepository;

    /** 대기 일지를 집어 가며 MATCHING 으로 바꾼다. 커밋되면 다음 실행이 같은 일지를 다시 집지 않는다. */
    @Transactional
    public List<Long> claimPending(int batchSize) {
        List<JournalEntry> targets = journalEntryRepository.findMatchingTargets(
                JournalEntryStatus.PENDING, Limit.of(batchSize));
        targets.forEach(JournalEntry::startMatching);
        return targets.stream().map(JournalEntry::getId).toList();
    }

    /** AI 입력은 저장하지 않고 그때그때 조립한다 (DB 스키마 §2.2). */
    @Transactional(readOnly = true)
    public MatchingAgentRequest prepareRequest(Long journalEntryId) {
        JournalEntry entry = journalEntryRepository.findByIdAndDeletedAtIsNull(journalEntryId)
                .orElseThrow(() -> new MatchingTargetException("journal entry not found: " + journalEntryId));
        RawRecord rawRecord = rawRecordRepository.findByIdAndDeletedAtIsNull(entry.getRawRecordId())
                .orElseThrow(() -> new MatchingTargetException("raw record not found: " + entry.getRawRecordId()));
        Long organizationId = organizationIdOf(rawRecord);

        List<MatchingAgentRequest.RosterEntry> roster = childRepository.findActiveByOrganizationId(organizationId)
                .stream()
                .map(child -> new MatchingAgentRequest.RosterEntry(
                        child.getId(), child.getName(), child.getBirthdate().toString()))
                .toList();

        return new MatchingAgentRequest(
                entry.getId(),
                entry.getContent(),
                roster,
                rawRecord.getId(),
                toText(entry.getEntryDate()),
                rawRecord.getHintName(),
                toText(rawRecord.getHintBirthdate()));
    }

    /** 집어 갔지만 처리하지 않은 일지를 대기로 돌려놓는다. 이미 다른 상태로 바뀐 일지는 건드리지 않는다. */
    @Transactional
    public void release(List<Long> journalEntryIds) {
        journalEntryRepository.findAllById(journalEntryIds).stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.MATCHING)
                .forEach(JournalEntry::releaseMatching);
    }

    /** 이전에 꺼진 앱이 처리하다 만 일지를 대기로 되돌린다. 워커가 첫 실행에서 한 번만 부른다. */
    @Transactional
    public int releaseStuck() {
        List<JournalEntry> stuck = journalEntryRepository.findByStatusAndDeletedAtIsNull(JournalEntryStatus.MATCHING);
        stuck.forEach(JournalEntry::releaseMatching);
        return stuck.size();
    }

    /**
     * raw_record.institution_id 는 organization.id 를 문자열로 담는다 (DB 스키마 §6.1). 9/22 이전 행에는 카카오
     * 회원번호가 들어 있어 숫자로 읽혀도 기관이 없을 수 있다 — 그대로 보내면 빈 명부로 unmatched 가 나와
     * 원인이 가려지므로 여기서 실패시킨다.
     */
    private Long organizationIdOf(RawRecord rawRecord) {
        Long organizationId;
        try {
            organizationId = Long.valueOf(rawRecord.getInstitutionId());
        } catch (NumberFormatException e) {
            throw new MatchingTargetException("raw record has invalid institution id: " + rawRecord.getId());
        }
        if (!organizationRepository.existsById(organizationId)) {
            throw new MatchingTargetException("organization not found for raw record: " + rawRecord.getId());
        }
        return organizationId;
    }

    private static String toText(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
