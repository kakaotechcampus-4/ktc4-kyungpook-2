package com.itda.backend.domain.insight;

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
 * 인사이트 한 건 (DB 스키마 §9.3). 승인된 요약들을 겹쳐 찾아낸 "상황 → 반응 → 대처와 결과" 묶음이다.
 *
 * <p><b>한 행이 인사이트 하나다.</b> 에이전트가 한 번 호출에 여러 건을 돌려주므로 한 번 실행이 여러 행이
 * 된다. 그래서 행 단위로 걸 자연키가 없고, 중복 실행은 {@link InsightRun} 의 UNIQUE 가 막는다 (§9.5).
 *
 * <p>JSON 조각({@code supports}·{@code claimIds}·{@code sourceEntryIds}·{@code sourceInstitutionIds})은
 * AI 가 보낸 그대로 TEXT 에 담는다 — {@code summary_result.claims} 와 같은 방식이다.
 *
 * <p><b>{@code sourceInstitutionIds} 는 근거를 낸 기관이지 받을 기관이 아니다.</b> 누구에게 보일지는
 * Gate 2 시점의 동의로 BE 가 계산한다 — 인사이트를 만든 뒤에도 동의가 바뀌기 때문이다. AI 가 수신 기관을
 * 추천하던 방식은 #137 에서 되돌렸다.
 *
 * <p>⚠️ <b>{@code content} 에 다른 아이 이름이 없다는 보장은 없다.</b> 요약 익명화와 인사이트 프롬프트로만
 * 막고 보장하는 층이 없다 ({@code AI/insight/CRITERIA.md} §5). Gate 2 화면이 그 사실을 교사에게 표시해야 한다.
 *
 * <p>실행 이력이라 수정하지도 삭제하지도 않는다. 상태만 Gate 2 에서 바뀐다 (§3.2).
 */
@Entity
@Table(name = "insight_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsightResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    /** 같은 아동·같은 기간의 몇 번째 생성인지. {@link InsightRun} 이 정한 값을 받아 쓴다. */
    @Column(nullable = false)
    private Integer revision;

    /** 어떤 상황에서. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String situation;

    /** 어떤 반응이 나타나는지. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String behavior;

    /** 교사·보호자가 읽을 본문. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 대처와 결과 JSON 문자열. 결과가 기록에 없는 대처는 AI 쪽 코드가 이미 버린다. */
    @Column(columnDefinition = "TEXT")
    private String supports;

    /** 근거가 된 claim JSON 문자열 {@code ["31-0", "34-2"]}. */
    @Column(name = "claim_ids", columnDefinition = "TEXT")
    private String claimIds;

    @Column(name = "source_entry_ids", columnDefinition = "TEXT")
    private String sourceEntryIds;

    @Column(name = "source_institution_ids", columnDefinition = "TEXT")
    private String sourceInstitutionIds;

    /** 두 기관 이상의 근거를 이었는지. */
    @Column(name = "cross_institution", nullable = false)
    private boolean crossInstitution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InsightStatus status;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private InsightResult(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision,
            String situation, String behavior, String content, String supports, String claimIds,
            String sourceEntryIds, String sourceInstitutionIds, boolean crossInstitution, String rawResponse) {
        this.childId = childId;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.revision = revision;
        this.situation = situation;
        this.behavior = behavior;
        this.content = content;
        this.supports = supports;
        this.claimIds = claimIds;
        this.sourceEntryIds = sourceEntryIds;
        this.sourceInstitutionIds = sourceInstitutionIds;
        this.crossInstitution = crossInstitution;
        this.rawResponse = rawResponse;
        this.status = InsightStatus.GATE2_PENDING;
    }

    public static InsightResult of(Long childId, LocalDate periodFrom, LocalDate periodTo, int revision,
            String situation, String behavior, String content, String supports, String claimIds,
            String sourceEntryIds, String sourceInstitutionIds, boolean crossInstitution, String rawResponse) {
        if (childId == null || periodFrom == null || periodTo == null) {
            throw new IllegalArgumentException("인사이트의 아동·기간은 필수입니다.");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("인사이트 판 번호는 1부터입니다: " + revision);
        }
        requireText(situation, "상황");
        requireText(behavior, "반응");
        requireText(content, "본문");
        return new InsightResult(childId, periodFrom, periodTo, revision, situation, behavior, content,
                supports, claimIds, sourceEntryIds, sourceInstitutionIds, crossInstitution, rawResponse);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            // 빈 글이 Gate 2 에 올라가면 교사가 판단할 수 없고, 승인되면 빈 글이 다른 기관으로 나간다.
            throw new IllegalArgumentException("인사이트 " + name + "은 필수입니다.");
        }
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
