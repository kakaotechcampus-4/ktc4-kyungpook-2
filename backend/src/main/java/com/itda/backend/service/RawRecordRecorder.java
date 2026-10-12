package com.itda.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.repository.RawRecordRepository;

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

    // REQUIRES_NEW — 이 클래스를 분리한 목적 자체가 ingest()의 외부 I/O와 무관한 독립 트랜잭션
    // 경계다. 호출자가 이미 트랜잭션 안에 있어도(지금은 없지만) 거기에 조용히 합류하면 안 된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RawRecord save(RawRecord rawRecord) {
        return rawRecordRepository.save(rawRecord);
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
