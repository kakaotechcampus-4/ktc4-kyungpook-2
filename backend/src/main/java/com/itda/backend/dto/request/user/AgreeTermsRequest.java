package com.itda.backend.dto.request.user;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.service.user.TermsCatalog;

/**
 * POST /api/v1/auth/terms 의 요청. 보호자가 동의한 약관 키만 보낸다 — 판은 서버가 채운다.
 *
 * <p>모르는 키({@code notify} 등)는 JSON 을 읽는 단계에서 실패한다. 필수 약관이 하나라도 빠지면
 * {@link #isAllRequiredAgreed()} 가 막는다. 실패는 전부 400 INVALID_REQUEST 다.
 */
public record AgreeTermsRequest(
        @ArraySchema(
                arraySchema = @Schema(description = "동의한 약관. 필수 약관(tos · privacy)이 모두 있어야 한다"),
                schema = @Schema(allowableValues = {"tos", "privacy"}))
        @NotEmpty
        List<@NotNull TermKey> agreed
) {

    /**
     * 목록이 없으면 {@code @NotEmpty} 가 따로 잡으므로 통과시킨다. is 로 시작해 getter 처럼 보이므로
     * {@code @JsonIgnore} 가 없으면 Swagger 요청 스키마에 없는 필드로 나타난다.
     */
    @JsonIgnore
    @AssertTrue
    public boolean isAllRequiredAgreed() {
        return agreed == null || agreed.containsAll(TermsCatalog.required());
    }
}
