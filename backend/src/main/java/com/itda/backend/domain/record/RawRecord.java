package com.itda.backend.domain.record;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "raw_record",
        indexes = @Index(name = "idx_raw_record_institution_id", columnList = "institution_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawRecord {

    public static final int MAX_TEXT_FIELD_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = MAX_TEXT_FIELD_LENGTH)
    private String institutionId;

    @Column(nullable = false, length = MAX_TEXT_FIELD_LENGTH)
    private String originalFilename;

    @Column(nullable = false)
    private String storedPath;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RawRecordStatus status;

    @Column(length = 100)
    private String hintName;

    private LocalDate hintBirthdate;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public RawRecord(
            String institutionId,
            String originalFilename,
            String storedPath,
            String contentType,
            long sizeBytes,
            RawRecordStatus status) {
        this.institutionId = institutionId;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void applyHint(String hintName, LocalDate hintBirthdate) {
        this.hintName = hintName;
        this.hintBirthdate = hintBirthdate;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
