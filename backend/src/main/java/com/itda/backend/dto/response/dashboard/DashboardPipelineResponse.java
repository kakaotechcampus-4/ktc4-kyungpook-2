package com.itda.backend.dto.response.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 대시보드 "선생님 차례"와 "진행 현황" (#370, api-spec O-80). 단계 판정은 BE 가 하고 FE 는 그대로 그린다.
 *
 * @param turn 선생님 차례 5줄의 건수
 * @param rows 진행 현황 줄. 내 차례 → AI 진행 중 → 대기 → 다른 사람 차례 → 끝 순, 같으면 단계·날짜 순
 */
public record DashboardPipelineResponse(Turn turn, List<Row> rows) {

    /**
     * @param confirm  아이 확인 — 매칭 확인 큐(O-22) 건수
     * @param blocked  고쳐야 할 기록 — 수정 요청 큐(O-24) 건수
     * @param review   검토 — 검토 대기 요약(다시 만드는 중 제외)과 요약 실패 묶음 수
     * @param approve  분석 승인 — 우리 기관이 승인할 Gate 2 대기(O-40) 수
     * @param requests 보호자 연결 요청(O-60) 수
     */
    public record Turn(int confirm, int blocked, int review, int approve, int requests) {
    }

    /**
     * 진행 현황 한 줄. 검토까지는 아이×날짜({@code entry}), 검토 승인 뒤에는 아이×주({@code week}) 한 줄이다.
     *
     * @param childId   아이가 아직 정해지지 않았으면 {@code null}
     * @param date      {@code entry} 줄의 일지 날짜
     * @param week      {@code week} 줄의 주(월~일)
     * @param step      0 아이 찾기 · 1 AI 확인 · 2 요약 · 3 검토 · 4 주간 분석 · 5 분석 승인
     * @param detail    줄마다 덧붙이는 값. 없으면 {@code null}
     * @param target    바로가기로 열 대상. 선생님 차례가 아니면 {@code null}
     */
    public record Row(Kind kind, String childId, String childName, LocalDate date, Week week, int step, Who who,
            Status status, Detail detail, Target target) {
    }

    public record Week(LocalDate from, LocalDate to, String label) {
    }

    /**
     * @param count        아이를 찾는 중인 그날 일지 수 ({@code matching})
     * @param kinds        막힌 이유 코드, 예: "개인정보표현"·"진단명" ({@code blocked})
     * @param reasons      막힌 이유 문장 ({@code blocked})
     * @param noContent    요약할 내용이 없다는 요약인지 ({@code review}·{@code review_held})
     * @param approverName 승인할 다른 기관 이름 ({@code review_elsewhere})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(Integer count, List<String> kinds, List<String> reasons, Boolean noContent,
            String approverName) {
    }

    /**
     * 바로가기 대상. {@code type}: {@code matching}(매칭 확인 큐 항목 id) · {@code validation}(수정 요청 큐 항목 id) ·
     * {@code summary}(요약 id) · {@code summary_failed}(id 없음 — 줄의 childId·date 로 O-35~37) · {@code insight}(인사이트 id).
     */
    public record Target(String type, String id) {
    }

    public enum Kind {
        ENTRY, WEEK;

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 누구 차례인지. 선언 순서가 줄 정렬 순서다. */
    public enum Who {
        ME, AI, WAIT, OTHER, DONE;

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 줄 상태와 그 상태의 기본 단계·차례. {@code system_error} 만 실패한 단계에 따라 단계가 바뀐다. */
    public enum Status {
        MATCHING(0, Who.AI),
        MATCH_REVIEW(0, Who.ME),
        VALIDATING(1, Who.AI),
        BLOCKED(1, Who.ME),
        WAITING_SAME_DATE(2, Who.WAIT),
        SUMMARIZING(2, Who.AI),
        SUMMARY_FAILED(2, Who.ME),
        SYSTEM_ERROR(2, Who.OTHER),
        REVIEW(3, Who.ME),
        REVIEW_HELD(3, Who.ME),
        REGENERATING(3, Who.AI),
        WAITING_WEEK_END(4, Who.WAIT),
        QUEUED(4, Who.AI),
        RUNNING(4, Who.AI),
        RETRYING(4, Who.AI),
        NOT_CONSENTED(4, Who.OTHER),
        NOTHING_NEW(4, Who.DONE),
        REVIEW_HERE(5, Who.ME),
        REVIEW_ELSEWHERE(5, Who.OTHER),
        INSIGHT_HELD(5, Who.ME),
        APPROVED(5, Who.DONE);

        private final int step;
        private final Who who;

        Status(int step, Who who) {
            this.step = step;
            this.who = who;
        }

        public int step() {
            return step;
        }

        public Who who() {
            return who;
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
