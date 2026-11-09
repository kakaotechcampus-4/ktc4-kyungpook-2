package com.itda.backend.service.record;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.record.RawRecordStatus;
import com.itda.backend.domain.record.RawRecordUpload;
import com.itda.backend.exception.record.RawRecordUploadNotFoundException;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.record.RawRecordUploadRepository;
import com.itda.backend.service.agent.AgentQueueGuard;
import com.itda.backend.service.user.LoginUserTransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RawRecordRecorder {

    private final RawRecordRepository rawRecordRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AgentQueueGuard agentQueueGuard;
    private final RawRecordUploadRepository rawRecordUploadRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawRecord accept(RawRecord rawRecord, Long organizationId, List<JournalEntrySplitter.SplitEntry> entries) {
        agentQueueGuard.requireRoom(organizationId, entries.size());
        RawRecord saved = rawRecordRepository.save(rawRecord);
        int seq = 1;
        for (JournalEntrySplitter.SplitEntry entry : entries) {
            journalEntryRepository.save(JournalEntry.of(saved.getId(), entry.entryDate(), entry.content(), seq++));
        }
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawRecord acceptUpload(Long uploadId, RawRecord rawRecord, Long organizationId,
            List<JournalEntrySplitter.SplitEntry> entries) {
        RawRecordUpload upload = rawRecordUploadRepository.findByIdForUpdate(uploadId)
                .orElseThrow(() -> new RawRecordUploadNotFoundException(uploadId));
        if (upload.isCompleted()) {
            return rawRecordRepository.findById(upload.getRawRecordId())
                    .orElseThrow(() -> new RawRecordUploadNotFoundException(uploadId));
        }
        RawRecord saved = accept(rawRecord, organizationId, entries);
        upload.complete(saved.getId());
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            String institutionId, String displayFilename, String storedPath, String contentType, long sizeBytes) {
        rawRecordRepository.save(new RawRecord(
                institutionId, displayFilename, storedPath, contentType, sizeBytes, RawRecordStatus.FAILED));
    }
}
