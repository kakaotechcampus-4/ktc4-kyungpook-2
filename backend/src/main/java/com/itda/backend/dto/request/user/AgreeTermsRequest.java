package com.itda.backend.dto.request.user;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.itda.backend.domain.user.TermKey;

/**
 * POST /api/v1/auth/terms 의 요청. 회원이 동의한 약관 키만 보낸다 — 판은 서버가 채운다.
 *
 * <p>모르는 키({@code notify} 등)는 JSON 을 읽는 단계에서 실패한다. 필수 약관은 역할마다 달라 이 DTO 는 모른다 —
 * 다른 역할의 약관이 섞이거나 필수가 빠진 것은 서비스({@code TermsService.agree})가 막는다. 실패는 전부 400 INVALID_REQUEST 다.
 */
public record AgreeTermsRequest(
        @ArraySchema(
                arraySchema = @Schema(description = "동의한 약관. 보호자는 tos · privacy, 기관 담당자는 "
                        + "org_tos · org_privacy · org_child_data 가 모두 있어야 하고 다른 역할의 약관은 섞을 수 없다"),
                schema = @Schema(allowableValues = {"tos", "privacy", "org_tos", "org_privacy", "org_child_data"}))
        @NotEmpty
        List<@NotNull TermKey> agreed
) {
}
