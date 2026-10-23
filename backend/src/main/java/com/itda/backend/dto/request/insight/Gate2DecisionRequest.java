package com.itda.backend.dto.request.insight;

import java.util.Locale;

import com.itda.backend.domain.approval.Gate2Action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/insights/{insightId}/gate2 의 요청. 교사의 발송 전 결정이다.
 *
 * <p>{@code targetInstitutionIds} 는 없다 — 10/17 회의에서 <b>기관이 수신처를 고르지 않고</b> 승인 뒤
 * 보호자가 기관별로 정하기로 했다 (#206). 승인하면 보호자에게 열람 권한이 생긴다.
 */
public record Gate2DecisionRequest(
        @Schema(allowableValues = {"approve", "hold"}, example = "approve")
        @NotNull
        @Pattern(regexp = "^(approve|hold)$")
        String decision
) {

    /** 검증을 통과한 뒤에만 부른다. */
    public Gate2Action action() {
        return Gate2Action.valueOf(decision.toUpperCase(Locale.ROOT));
    }
}
