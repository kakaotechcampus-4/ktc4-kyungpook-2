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

@Entity
@Table(name = "raw_record_upload",
        indexes = @Index(name = "idx_raw_record_upload_created_at", columnList = "created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawRecordUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = RawRecord.MAX_TEXT_FIELD_LENGTH)
    private String originalFilename;

    @Column(nullable = false)
    private String storedPath;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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
