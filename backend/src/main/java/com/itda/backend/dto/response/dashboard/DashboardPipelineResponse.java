package com.itda.backend.dto.response.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

public record DashboardPipelineResponse(Turn turn, List<Row> rows) {

    public record Turn(int confirm, int blocked, int review, int approve, int requests) {
    }

    public record Row(Kind kind, String childId, String childName, LocalDate date, Week week, int step, Who who,
            Status status, Detail detail, Target target) {
    }

    public record Week(LocalDate from, LocalDate to, String label) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(Integer count, List<String> kinds, List<String> reasons, Boolean noContent,
            String approverName) {
    }

    public record Target(String type, String id) {
    }

    public enum Kind {
        ENTRY, WEEK;

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Who {
        ME, AI, WAIT, OTHER, DONE;

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

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
