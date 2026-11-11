package com.itda.backend.dto.request.user;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.itda.backend.domain.user.TermKey;

public record AgreeTermsRequest(
        @ArraySchema(
                arraySchema = @Schema(description = "동의한 약관. 보호자는 tos · privacy, 기관 담당자는 "
                        + "org_tos · org_privacy · org_child_data 가 모두 있어야 하고 다른 역할의 약관은 섞을 수 없다"),
                schema = @Schema(allowableValues = {"tos", "privacy", "org_tos", "org_privacy", "org_child_data"}))
        @NotEmpty
        List<@NotNull TermKey> agreed,

        @Schema(description = "약관마다 동의 화면이 읽은 판(GET /api/v1/terms 의 version). 키는 agreed 와 같아야 한다. "
                + "하나라도 지금 판과 다르면 409 TERMS_VERSION_CHANGED. 곧 필수가 된다",
                example = "{\"tos\": \"2026-11-07\", \"privacy\": \"2026-11-09\"}")
        Map<@NotNull TermKey, @NotNull String> versions
) {
}
