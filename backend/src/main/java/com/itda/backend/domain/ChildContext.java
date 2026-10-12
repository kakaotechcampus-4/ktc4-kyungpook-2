package com.itda.backend.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Gate 1 을 통과한 요약을 아동별로 쌓아 둔 한 건 (DB 스키마 §9.2).
 *
 * <p>다른 기관 담당자가 아동 타임라인(O-14)에서 읽는 자리이고, 인사이트도 원본 일지가 아니라
 * 여기를 재료로 쓴다. 다른 테이블은 FK·연관관계 없이 ID 컬럼으로만 가리킨다 (§16).
 *
 * <p><b>{@code content} 가 {@code summary_result.content} 와 따로 있는 이유</b> — 교사가 문구를
 * 고쳐서 승인할 수 있다. AI 가 쓴 원문은 {@code summary_result} 에 그대로 두고, 사람이 승인한
 * 최종본만 여기 담는다. 한쪽에 몰아 담으면 {@code matching_result} 가 사람 수정으로 AI 원판정을
 * 잃었던 문제(§0.3)를 되풀이하게 된다.
 *
 * <p>승인 이력이라 수정하지도 삭제하지도 않는다. 그래서 {@code updated_at}·{@code deleted_at} 이 없다 (§3.2).
 *
 * <p><b>요약 하나에 승인본은 하나다</b> — {@code summary_result_id} 에 UNIQUE 를 건다. 승인된 요약을
 * 다시 승인하는 경로가 없기 때문이다(§8.2): 반려 상태를 두지 않고, 승인 뒤에 새 일지가 오면 그 요약을
 * 고치는 게 아니라 {@code revision + 1} 로 <b>새 요약</b>을 만든다. 그러면 승인본도 다른 요약을 가리킨다.
 * 제약이 없으면 Gate 1 승인 API 를 두 번 호출했을 때 같은 글이 조용히 두 번 쌓인다(코드리뷰로 확인).
 */
@Entity
@Table(
        name = "child_context",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_child_context_summary_result", columnNames = "summary_result_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    /**
     * 출처가 된 요약. 날짜·기관·판수(revision)·재료 일지는 이 ID 로 {@code summary_result} 를 찾아 쓴다
     * — 타임라인 화면이 쓰는 값이 전부 거기 있어서 여기에 다시 담지 않는다.
     */
    @Column(name = "summary_result_id", nullable = false)
    private Long summaryResultId;

    /** 교사가 승인한 최종본. 고쳐서 승인했다면 고친 글이 들어온다. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private ChildContext(Long childId, Long summaryResultId, String content) {
        this.childId = childId;
        this.summaryResultId = summaryResultId;
        this.content = content;
    }

    public static ChildContext of(Long childId, Long summaryResultId, String content) {
        if (childId == null) {
            throw new IllegalArgumentException("대상 아동 ID는 필수입니다.");
        }
        if (summaryResultId == null) {
            throw new IllegalArgumentException("출처가 된 요약 ID는 필수입니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("승인된 요약 본문은 필수입니다.");
        }
        return new ChildContext(childId, summaryResultId, content);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
