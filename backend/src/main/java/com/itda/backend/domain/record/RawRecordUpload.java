package com.itda.backend.domain.record;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 브라우저가 저장소로 바로 올리는 파일 하나의 접수 (#450). 업로드 주소를 줄 때 만들고, 완료를 확인하면 원본 기록을 만들어
 * {@link #rawRecordId} 에 남긴다.
 *
 * <p>원본 기록({@link RawRecord}) 에 "올리는 중" 상태를 더하지 않고 따로 둔다. {@code raw_record.status} 에는 Hibernate 가
 * 만든 CHECK 제약이 있어 새 값을 넣으려면 배포 DB 를 손으로 고쳐야 하고, 목록 · 처리 현황 · 대시보드가 모두 "올리는 중"을
 * 빼야 한다. 이 테이블은 새로 생겨 {@code ddl-auto} 가 만들고, 원본 기록은 지금처럼 다 올라온 파일만 남는다.
 *
 * <p>완료되지 않은 접수는 하루 뒤 저장소 파일과 함께 지운다 ({@code AbandonedUploadSweeper}). 완료된 접수도 하루 뒤 행만 지운다 —
 * 같은 완료 요청이 다시 와도 원본 기록을 두 번 만들지 않는 것은 그 사이만 지키면 된다.
 */
@Entity
@Table(name = "raw_record_upload",
        indexes = @Index(name = "idx_raw_record_upload_created_at", columnList = "created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawRecordUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 접수한 사용자의 소속 기관. 완료는 같은 기관 사용자만 할 수 있다. */
    @Column(nullable = false)
    private Long organizationId;

    /** 접수한 사용자. 기록용이다. */
    @Column(nullable = false)
    private Long userId;

    /** 화면에 보이는 파일 이름. 원본 기록의 {@code original_filename} 과 같은 규칙으로 다듬은 값이다. */
    @Column(nullable = false, length = RawRecord.MAX_TEXT_FIELD_LENGTH)
    private String originalFilename;

    /** 저장소 안 이름(UUID). 클라이언트가 보낸 이름은 쓰지 않는다. */
    @Column(nullable = false)
    private String storedPath;

    /** 업로드 주소에 서명한 형식. 브라우저는 이 값으로 올려야 한다. */
    @Column(nullable = false)
    private String contentType;

    /** 업로드 주소에 서명한 크기. 완료할 때 저장소의 실제 크기와 맞춰 본다. */
    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 완료로 만든 원본 기록. 완료 전이면 {@code null} 이다. */
    private Long rawRecordId;

    private LocalDateTime completedAt;

    public RawRecordUpload(Long organizationId, Long userId, String originalFilename, String storedPath,
            String contentType, long sizeBytes) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isCompleted() {
        return rawRecordId != null;
    }

    public void complete(Long rawRecordId) {
        this.rawRecordId = rawRecordId;
        this.completedAt = LocalDateTime.now();
    }
}
