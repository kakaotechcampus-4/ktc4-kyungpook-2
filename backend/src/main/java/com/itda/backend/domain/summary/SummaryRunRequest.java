package com.itda.backend.domain.summary;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "summary_run_request",
        uniqueConstraints = @UniqueConstraint(
                name = SummaryRunRequest.UK_GROUP,
                columnNames = {"child_id", "entry_date", "institution_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SummaryRunRequest {

    public static final String UK_GROUP = "uk_summary_run_request_group";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "handled_until")
    private LocalDateTime handledUntil;

    private SummaryRunRequest(Long childId, LocalDate entryDate, Long institutionId, LocalDateTime requestedAt) {
        this.childId = childId;
        this.entryDate = entryDate;
        this.institutionId = institutionId;
        this.requestedAt = requestedAt;
    }

    public static SummaryRunRequest of(Long childId, LocalDate entryDate, Long institutionId,
            LocalDateTime requestedAt) {
        if (childId == null || entryDate == null || institutionId == null) {
            throw new IllegalArgumentException("요약 묶음(아동·날짜·기관)은 필수입니다.");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("요청 시각은 필수입니다.");
        }
        return new SummaryRunRequest(childId, entryDate, institutionId, requestedAt);
    }

    public boolean isPending() {
        return handledUntil == null || requestedAt.isAfter(handledUntil);
    }
}
