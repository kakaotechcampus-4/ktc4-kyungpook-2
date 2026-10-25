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
 * <p>요약은 아직 처리하지 않은 요청이 있는 묶음만 한다 — 자동 요약이 없어서 요청이 사라지면 그 묶음은 요약되지
 * 않는다. 그래서 메모리가 아니라 DB 에 둔다.
 *
 * <p>처리해도 지우지 않는다 (§3.2). 어디까지 처리했는지({@code handled_until})만 남기고, 그 뒤에 누른 것
 * ({@code requested_at > handled_until})이 아직 처리하지 않은 요청이다.
 *
 * <p>일지에 표시하지 않고 행을 따로 두는 이유 — 업로드 직후 누르면 일지가 아직 매칭 전이라 {@code child_id} 가
 * 비어 있어 어느 아이의 요청인지 일지에 적을 수 없다. {@code summary_result} 는 요약이 끝나야 생긴다.
 *
 * <p>묶음마다 한 행이다. 다시 누르면 {@code requested_at} 만 바뀐다. 두 시각은 엔티티를 고쳐 저장하지 않고 칸 하나씩
 * 조건부 UPDATE 로 바꾼다 ({@link com.itda.backend.repository.summary.SummaryRunRequestRepository}) — 교사가 다시
 * 누르는 것과 워커가 처리 표시를 하는 것이 겹쳐도 서로의 칸을 옛 값으로 덮지 않고, 시각은 앞으로만 간다.
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

    /**
     * 이 시각까지 누른 요청은 처리했다. 워커가 요약을 집은 시각이다. 아직 한 번도 처리하지 않았으면 {@code null}.
     */
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

    /** 아직 처리하지 않은 요청이 있는지. */
    public boolean isPending() {
        return handledUntil == null || requestedAt.isAfter(handledUntil);
    }
}
