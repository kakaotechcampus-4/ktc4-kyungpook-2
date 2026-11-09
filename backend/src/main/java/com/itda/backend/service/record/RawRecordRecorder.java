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

/**
 * RawRecord 저장 전용. DB 쓰기를 {@link RawRecordService#ingest}의 파일 저장소(S3) 호출과
 * 분리해 트랜잭션 경계를 명시한다(backend/AGENTS.md) — 파일 업로드 같은 외부 I/O를 DB
 * 트랜잭션 안에 묶어두면 커넥션을 불필요하게 오래 붙잡는다.
 */
@Service
@RequiredArgsConstructor
public class RawRecordRecorder {

    private final RawRecordRepository rawRecordRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AgentQueueGuard agentQueueGuard;
    private final RawRecordUploadRepository rawRecordUploadRepository;

    /**
     * 받은 파일의 원본 행과 거기서 나온 일지를 한 트랜잭션에 저장한다 (#424).
     *
     * <p>먼저 기관 행을 잠그고 AI 처리 줄에 자리가 있는지 다시 센다({@link AgentQueueGuard#requireRoom}). 같은 기관이 동시에 올린
     * 파일은 여기서 한 줄로 서고, 뒤 요청은 앞 요청이 커밋한 일지까지 센다. 그래서 센 수와 실제로 들어간 일지가 같도록 원본 행과
     * 일지를 함께 저장한다 — 일지 저장이 실패하면 원본 행도 남기지 않고, 부르는 쪽이 FAILED 행으로 따로 추적한다.
     *
     * <p>REQUIRES_NEW — 이 클래스를 분리한 목적 자체가 ingest()의 외부 I/O와 무관한 독립 트랜잭션 경계다. 호출자가 이미
     * 트랜잭션 안에 있어도(지금은 없지만) 거기에 조용히 합류하면 안 된다.
     *
     * @param entries 날짜를 다 채운 일지. 비어 있으면(글자를 못 뽑은 파일) 자리를 보지 않고 원본 행만 저장한다
     * @throws com.itda.backend.exception.agent.AgentQueueFullException 자리가 없다. 아무것도 저장하지 않는다
     */
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

    /**
     * 저장소로 바로 올린 파일을 받는다 (#450). {@link #accept} 와 같은 저장에 접수 완료 표시를 더한 것이다.
     *
     * <p>접수 행을 먼저 잠근다 — 같은 접수의 완료가 동시에 두 번 오면 뒤 요청은 여기서 기다렸다가 앞 요청이 만든 원본 기록을 그대로
     * 돌려받는다. 원본 기록이 두 개 생기지 않는다. 잠그는 순서는 접수 → 기관이다(기관은 {@link AgentQueueGuard#requireRoom}).
     *
     * @throws RawRecordUploadNotFoundException 그사이 접수가 지워졌다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawRecord acceptUpload(Long uploadId, RawRecord rawRecord, Long organizationId,
            List<JournalEntrySplitter.SplitEntry> entries) {
        RawRecordUpload upload = rawRecordUploadRepository.findByIdForUpdate(uploadId)
                .orElseThrow(() -> new RawRecordUploadNotFoundException(uploadId));
        if (upload.isCompleted()) {
            return rawRecordRepository.findById(upload.getRawRecordId())
                    .orElseThrow(() -> new RawRecordUploadNotFoundException(uploadId));
        }
        // 같은 트랜잭션 안에서 부른다 — 프록시를 거치지 않아 REQUIRES_NEW 가 새로 열리지 않는다
        RawRecord saved = accept(rawRecord, organizationId, entries);
        upload.complete(saved.getId());
        return saved;
    }

    /**
     * DB 저장 실패 후 FAILED 상태를 남긴다. {@link LoginUserTransactionService}와 같은 이유로
     * REQUIRES_NEW를 쓴다 — 실패한(제약 위반 등) 트랜잭션을 그대로 재사용하면 이 저장 시도도
     * 같이 막힐 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            String institutionId, String displayFilename, String storedPath, String contentType, long sizeBytes) {
        rawRecordRepository.save(new RawRecord(
                institutionId, displayFilename, storedPath, contentType, sizeBytes, RawRecordStatus.FAILED));
    }
}
