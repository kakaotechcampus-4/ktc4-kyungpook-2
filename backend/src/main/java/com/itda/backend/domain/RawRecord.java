package com.itda.backend.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "raw_record")
@Getter
@NoArgsConstructor
public class RawRecord {

    // RawRecordService의 저장 전 길이 검증과 반드시 같은 값을 써야 한다.
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

    /** 파일 표지에서 파싱한 아동 이름. 확정값이 아니라 매칭 힌트라 매칭 결과로 덮어쓰지 않는다 (DB 스키마 §6.1). */
    @Column(length = 100)
    private String hintName;

    /** 파일 표지에서 파싱한 생년월일. 표지가 없거나 파싱 전이면 null 이다. */
    private LocalDate hintBirthdate;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * DB 는 NULL 을 허용한다 — 배포 DB 에 이미 행이 있어서 {@code ddl-auto: update} 가 NOT NULL
     * 컬럼을 추가하지 못한다. 새 행과 수정되는 행은 항상 채워진다.
     */
    private LocalDateTime updatedAt;

    /** 삭제 표시. null 이면 살아 있는 행이다. 조회할 때는 반드시 {@code DeletedAtIsNull} 을 붙인다. */
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

    /** 파일명에서 뽑아낸 표지 힌트를 채운다. 명부 이름과 대조해서 찾은 값만 들어온다(RawRecordService). */
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
