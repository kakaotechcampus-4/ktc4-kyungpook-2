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

@Slf4j
@Service
@RequiredArgsConstructor
public class RemovedRecordPurgeService {

    static final int RETENTION_DAYS = 30;

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

        log.info("purged gate1-removed data entries={} summaries={} rawRecords={} cutoff={}",
                entries.size(), summaries.size(), rawRecords.size(), cutoff);
        return new Purged(entries.size(), rawRecords.stream().map(RawRecord::getStoredPath).toList());
    }

    private static Set<Long> idsOf(List<JournalEntry> entries, Function<JournalEntry, Long> id) {
        return entries.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public record Purged(int entries, List<String> storedKeys) {

        static final Purged NONE = new Purged(0, List.of());
    }
}
