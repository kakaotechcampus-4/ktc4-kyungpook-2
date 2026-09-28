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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 원본 파일 안의 관찰 기록 한 건. 매칭·검증의 처리 단위다.
 *
 * <p>기관 컬럼이 없다 — 어느 기관의 일지인지는 {@code rawRecordId} 로 {@code raw_record} 를 거쳐 찾는다.
 *
 * <p>{@code rawRecordId} 는 플랫폼 직접 입력(원본 파일 없음)이라 NULL 일 수 있고,
 * {@code childId} 는 매칭이 확정되기 전까지 NULL 이다 (DB 스키마 §6.2).
 */
@Entity
@Table(name = "journal_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_record_id")
    private Long rawRecordId;

    private LocalDate entryDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private Integer sequenceNo;

    @Column(name = "child_id")
    private Long childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JournalEntryStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private JournalEntry(Long rawRecordId, LocalDate entryDate, String content, Integer sequenceNo) {
        this.rawRecordId = rawRecordId;
        this.entryDate = entryDate;
        this.content = content;
        this.sequenceNo = sequenceNo;
        this.status = JournalEntryStatus.PENDING;
    }

    public static JournalEntry of(Long rawRecordId, LocalDate entryDate, String content, Integer sequenceNo) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("일지 내용은 필수입니다.");
        }
        return new JournalEntry(rawRecordId, entryDate, content, sequenceNo);
    }

    /** 매칭이 확정되면 누구의 기록인지 채운다. 아동의 존재·소속 검증은 호출하는 Service 책임이다. */
    public void assignChild(Long childId) {
        if (childId == null) {
            throw new IllegalArgumentException("확정할 아동 ID는 필수입니다.");
        }
        this.childId = childId;
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

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
