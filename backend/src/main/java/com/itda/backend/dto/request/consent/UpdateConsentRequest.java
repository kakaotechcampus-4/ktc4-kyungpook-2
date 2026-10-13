package com.itda.backend.dto.request.consent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * G-41 동의 부여·철회.
 *
 * <p>{@code List<@NotNull ConsentField>} 는 배열 <b>안의</b> {@code null} 만 막는다. 배열이 없거나 비어 있는
 * 경우는 {@link #isAllowedFieldsValidForAction()} 이 따로 본다 — grant 에서만 필수이고 revoke 는 생략해도 된다.
 * 모르는 항목·모르는 action 은 JSON 을 읽는 단계에서 실패한다. 실패는 전부 400 INVALID_REQUEST 다.
 */
public record UpdateConsentRequest(
        @Schema(example = "grant", allowableValues = {"grant", "revoke"})
        @NotNull
        ConsentAction action,

        @ArraySchema(
                arraySchema = @Schema(description = "공유를 허락할 항목. grant 에서 필수(1개 이상), revoke 에서는 무시한다"),
                schema = @Schema(allowableValues = {"daily_summary", "weekly_insight"}))
        List<@NotNull ConsentField> allowedFields
) {

    /**
     * grant 면 항목이 한 개 이상 있어야 한다. {@code action} 이 없으면 {@code @NotNull} 이 따로 잡으므로 통과시킨다.
     *
     * <p>예외가 검증기 밖으로 새면 400 이 아니라 500 이 되므로 실패는 false 로만 알린다. is 로 시작해 getter
     * 처럼 보이므로 {@code @JsonIgnore} 가 없으면 Swagger 요청 스키마에 없는 필드로 나타난다.
     */
    @JsonIgnore
    @AssertTrue
    public boolean isAllowedFieldsValidForAction() {
        if (action != ConsentAction.GRANT) {
            return true;
        }
        return allowedFields != null && !allowedFields.isEmpty();
    }
}
