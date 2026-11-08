package com.itda.backend.dto.request.user;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.itda.backend.domain.user.TermKey;

/**
 * POST /api/v1/auth/terms 의 요청. 회원이 동의한 약관 키와, 동의 화면이 읽은 약관의 판.
 *
 * <p>{@code versions} 를 보내면 그 판이 지금 판인지 서버가 확인하고 그 판으로 기록한다(다르면 409). 보내지 않으면
 * 본문을 보여 주지 못한 옛 화면으로 보고 라벨 판으로 기록한다 — 프론트가 판을 보내게 된 뒤 필수로 바꾼다.
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
        List<@NotNull TermKey> agreed,

        @Schema(description = "약관마다 동의 화면이 읽은 판(GET /api/v1/terms 의 version). 키는 agreed 와 같아야 한다. "
                + "하나라도 지금 판과 다르면 409 TERMS_VERSION_CHANGED. 곧 필수가 된다",
                example = "{\"tos\": \"2026-11-07\", \"privacy\": \"2026-11-07\"}")
        Map<@NotNull TermKey, @NotNull String> versions
) {
}
