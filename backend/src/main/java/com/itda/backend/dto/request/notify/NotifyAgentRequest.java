package com.itda.backend.dto.request.notify;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code AI POST /notify} 요청 (#419).
 *
 * <p><b>보호자 이메일이 없다.</b> {@code childId} 만 보내고 주소는 AI 쪽 MCP 서버가 찾는다 — 주소가
 * 모델 문맥에 들어가면 지어낸 주소로 아이 기록이 나갈 수 있다 (AI/notify/CRITERIA.md §2).
 * BE 는 주소를 모른 채로 끝난다.
 */
public record NotifyAgentRequest(
        @JsonProperty("child_id") Long childId,
        @JsonProperty("child_name") String childName,
        @JsonProperty("period_from") String periodFrom,
        @JsonProperty("period_to") String periodTo,
        List<ApprovedInsight> insights,
        /**
         * 이 발송 묶음을 가리키는 키. <b>멱등성이 여기 달려 있다</b> — 같은 값으로 다시 오면 AI 는
         * 보내지 않고 처음 {@code message_id} 를 돌려준다. {@code insight_notification} 의 id 를 쓴다.
         */
        @JsonProperty("notification_id") String notificationId,
        /** 본문에 남았는지 대조할 다른 아이 이름. 모델 프롬프트에는 안 들어간다. */
        @JsonProperty("other_child_names") List<String> otherChildNames) {

    /** 승인된 인사이트 하나. 메일에 필요한 칸만 보낸다. */
    public record ApprovedInsight(
            String content,
            @JsonProperty("institution_types") List<String> institutionTypes,
            @JsonProperty("cross_institution") boolean crossInstitution) {
    }
}
