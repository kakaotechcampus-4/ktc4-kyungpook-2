package com.itda.backend.dto.response.insight;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

public record InsightProgressResponse(
        String childId,
        String childName,
        String periodFrom,
        String periodTo,
        String period,
        Status status,
        String message) {

    public static InsightProgressResponse of(Long childId, String childName, String periodFrom, String periodTo,
            String period, Status status) {
        return new InsightProgressResponse(String.valueOf(childId), childName, periodFrom, periodTo, period, status,
                status.message);
    }

    public enum Status {
        NOT_CONSENTED("보호자가 주간 리포트 공유를 허락하지 않아 우리 기관 기록은 주간 리포트에 쓰지 않아요"),
        WAITING_WEEK_END("이번 주가 끝나면 주간 리포트를 만들어요"),
        QUEUED("곧 주간 리포트를 만들기 시작해요"),
        RUNNING("주간 리포트를 만들고 있어요"),
        RETRYING("주간 리포트를 만들다 잠시 실패해 다시 시도할 예정이에요"),
        DONE("주간 리포트가 나와 승인 차례로 넘어갔어요"),
        NOTHING_NEW("이번 주는 새로 알릴 내용이 없어요");

        private final String message;

        Status(String message) {
            this.message = message;
        }

        @JsonValue
        public String toJson() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
