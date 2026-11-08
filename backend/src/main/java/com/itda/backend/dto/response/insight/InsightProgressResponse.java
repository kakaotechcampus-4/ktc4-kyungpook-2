package com.itda.backend.dto.response.insight;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 우리 기관이 승인한 기록이 들어가는 인사이트 분석 한 주의 진행 상태 (api-spec O-43).
 *
 * @param period  화면에 그대로 쓰는 "2026년 8월 3주차" 같은 문구 (O-40 과 같다)
 * @param message 화면에 그대로 쓰는 안내 문장. 상태마다 하나다
 */
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
        /** 보호자가 우리 기관에 주간 인사이트 공유를 허락하지 않았다. 우리 기록은 재료가 되지 않는다 (#257). */
        NOT_CONSENTED("보호자가 주간 리포트 공유를 허락하지 않아 우리 기관 기록은 주간 리포트에 쓰지 않아요"),
        /** 끝나지 않은 주다. 워커는 주가 끝난 뒤에 본다 ({@code include-current-week} 가 꺼져 있을 때). */
        WAITING_WEEK_END("이번 주가 끝나면 주간 리포트를 만들어요"),
        /** 아직 안 집었거나, 지난 분석 뒤에 새로 승인된 기록이 있어 다시 돌 차례다. */
        QUEUED("곧 주간 리포트를 만들기 시작해요"),
        RUNNING("주간 리포트를 만들고 있어요"),
        /** 마지막 시도가 실패했다. 워커가 잠시 뒤 다시 집는다 (#266). 실패 사유는 내부 메시지라 내보내지 않는다. */
        RETRYING("주간 리포트를 만들다 잠시 실패해 다시 시도할 예정이에요"),
        DONE("주간 리포트가 나와 승인 차례로 넘어갔어요"),
        /** 끝났지만 인사이트가 0건이다. 정상이다 — 억지로 만든 패턴이 과잉해석이다 (CRITERIA §3). */
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
