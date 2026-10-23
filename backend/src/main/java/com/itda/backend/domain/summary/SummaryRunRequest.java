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

/**
 * 교사가 묶음(아동 × 날짜 × 기관)에 누른 [요약] 요청 (DB 스키마 §8.3, #181).
 *
 * <p>요약은 이 요청이 있는 묶음만 한다 — 자동 요약이 없어서 요청이 사라지면 그 묶음은 요약되지 않는다. 그래서
 * 메모리가 아니라 DB 에 둔다. 워커가 결과를 남길 때 지운다.
 *
 * <p>일지에 표시하지 않고 행을 따로 두는 이유 — 업로드 직후 누르면 일지가 아직 매칭 전이라 {@code child_id} 가
 * 비어 있어 어느 아이의 요청인지 일지에 적을 수 없다. {@code summary_result} 는 요약이 끝나야 생긴다.
 *
 * <p>묶음마다 한 행이다. 다시 누르면 {@code requested_at} 만 바뀐다.
 */
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

    /** organization.id. {@code summary_result.institution_id} 와 같은 값이다. */
    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    /**
     * 마지막으로 누른 시각. 워커는 요약을 집은 시각보다 늦게 누른 요청은 결과를 남길 때 지우지 않는다 — 요약하는
     * 사이 들어온 일지를 보고 다시 누른 것이라, 그 일지는 방금 요약에 들어 있지 않다.
     */
    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

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

    /** 같은 묶음을 다시 눌렀다. */
    public void requestAgain(LocalDateTime requestedAt) {
        if (requestedAt == null) {
            throw new IllegalArgumentException("요청 시각은 필수입니다.");
        }
        this.requestedAt = requestedAt;
    }
}
