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

    /**
     * 승인된 인사이트 하나.
     *
     * <p><b>보호자 리포트 화면(G-36)과 같은 값을 보낸다.</b> 메일과 화면이 다른 글이면 보호자가
     * 둘을 견주다 헷갈린다 — 같은 것을 두 곳에서 보여 주는 것이다.
     */
    public record ApprovedInsight(
            String content,
            /** Gate 2 에서 승인한 기관 이름. 화면 맨 위에 뜨는 그 기관이다. 기관 행이 없으면 빈 문자열. */
            @JsonProperty("institution_name") String institutionName,
            /** {@code "2026년 10월 3주차"}. 화면 부제와 **같은 문구**다 (GuardianInsightService.periodLabel). */
            @JsonProperty("period_label") String periodLabel,
            @JsonProperty("institution_types") List<String> institutionTypes,
            @JsonProperty("cross_institution") boolean crossInstitution,
            /** 이 글의 근거. 화면의 "이 글의 근거" 절과 같은 목록이고 순서도 같다. */
            List<EvidenceEntry> evidence) {
    }

    /** 근거 일지 한 건. 화면의 근거 한 줄과 같다. */
    public record EvidenceEntry(
            @JsonProperty("institution_name") String institutionName,
            String date,
            @JsonProperty("looked_up") boolean lookedUp,
            List<EvidenceSentence> sentences) {
    }

    /**
     * 그 일지에서 이 글이 실제로 인용한 문장.
     *
     * <p><b>Gate 1 에서 교사가 승인한 요약 문장이다.</b> 원본 일지 인용이 아니다 — 원본에는 다른
     * 아이 이름이 남아 있어 밖으로 내보내지 않는다 ({@code AI/insight/CRITERIA.md §2}).
     */
    public record EvidenceSentence(String text, String role) {
    }
}
