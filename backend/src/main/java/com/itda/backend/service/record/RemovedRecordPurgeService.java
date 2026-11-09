package com.itda.backend.service.record;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.domain.summary.SummaryStatus;
import com.itda.backend.repository.matching.MatchingResultRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate 1 에서 반려(제거)한 일지의 데이터를 실제로 지운다 (#432, 멘토링 피드백).
 *
 * <p>반려하면 요약은 {@code REMOVED}, 일지는 삭제 표시만 되고 매칭 · 검증 결과와 원본이 그대로 남는다. 반려는 되돌릴 수
 * 없고(#178) 이 데이터를 다시 읽는 곳도 없어서, 아이 기록이 담긴 행만 쌓인다. 그래서 soft delete 원칙(DB 스키마 §3.2)의
 * 예외로 반려한 지 {@link #RETENTION_DAYS}일이 지나면 지운다. 누가 언제 반려했는지({@code human_review})는 남긴다.
 *
 * <p>외래 키가 없으므로 순서와 남은 참조를 여기서 본다 — 검증 → 매칭 → 일지 → 요약(가리키는 일지가 없을 때) →
 * 원본 행(그 파일의 일지가 하나도 안 남았을 때). 저장소 파일은 이 트랜잭션이 커밋된 뒤에 부르는 쪽이 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemovedRecordPurgeService {

    /** 반려한 뒤 지우기까지 기다리는 날 수. 팀 결정값이다 (#432). */
    static final int RETENTION_DAYS = 30;

    /** 한 트랜잭션에서 지우는 일지 수. 부르는 쪽은 이만큼 지워졌으면 한 번 더 부른다. */
    static final int BATCH_SIZE = 500;

    private final JournalEntryRepository journalEntryRepository;
    private final MatchingResultRepository matchingResultRepository;
    private final ValidationResultRepository validationResultRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final RawRecordRepository rawRecordRepository;
    private final Clock clock;

    @Transactional
    public Purged purgeBatch() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(RETENTION_DAYS);
        List<JournalEntry> entries = journalEntryRepository.findRemovedAtGate1Before(cutoff, Limit.of(BATCH_SIZE));
        if (entries.isEmpty()) {
            return Purged.NONE;
        }
        List<Long> entryIds = entries.stream().map(JournalEntry::getId).toList();

        validationResultRepository.deleteByJournalEntryIds(entryIds);
        matchingResultRepository.deleteByJournalEntryIds(entryIds);
        journalEntryRepository.deleteAllInBatch(entries);

        List<SummaryResult> summaries = summaryResultRepository.findAllById(idsOf(entries, JournalEntry::getSummaryId))
                .stream()
                .filter(summary -> summary.getStatus() == SummaryStatus.REMOVED)
                .filter(summary -> !journalEntryRepository.existsBySummaryId(summary.getId()))
                .toList();
        summaryResultRepository.deleteAllInBatch(summaries);

        List<RawRecord> rawRecords = rawRecordRepository.findAllById(idsOf(entries, JournalEntry::getRawRecordId))
                .stream()
                .filter(raw -> !journalEntryRepository.existsByRawRecordId(raw.getId()))
                .toList();
        rawRecordRepository.deleteAllInBatch(rawRecords);

        // 내용은 남기지 않는다 — 몇 건을 지웠는지만.
        log.info("purged gate1-removed data entries={} summaries={} rawRecords={} cutoff={}",
                entries.size(), summaries.size(), rawRecords.size(), cutoff);
        return new Purged(entries.size(), rawRecords.stream().map(RawRecord::getStoredPath).toList());
    }

    private static Set<Long> idsOf(List<JournalEntry> entries, Function<JournalEntry, Long> id) {
        return entries.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /**
     * @param entries    이번에 지운 일지 수
     * @param storedKeys 행을 지운 원본의 저장소 키. 커밋 뒤에 저장소에서 지운다
     */
    public record Purged(int entries, List<String> storedKeys) {

        static final Purged NONE = new Purged(0, List.of());
    }
}
