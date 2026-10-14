package com.itda.backend.dto.request.summary;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.itda.backend.domain.approval.Gate1Action;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/summaries/{summaryId}/gate1 의 요청. 교사의 Gate 1 결정이다.
 *
 * <p>{@code editedContent} 는 승인할 때만 보낸다. 없거나 AI 원문과 같으면 그대로 승인, 한 글자라도 다르면 수정 후
 * 승인이다. 보류·제거에 보내면 버려지므로 400 으로 막는다. {@code version} 은 목록에서 받은 값 그대로다.
 * 실패는 전부 400 INVALID_REQUEST 다.
 */
public record Gate1DecisionRequest(
        @Schema(allowableValues = {"approve", "hold", "remove"}, example = "approve")
        @NotNull
        @Pattern(regexp = "^(approve|hold|remove)$")
        String decision,

        @Schema(description = "고친 본문. 승인할 때만 보낸다.", nullable = true)
        String editedContent,

        @Schema(description = "목록에서 받은 version 그대로. 그 사이 요약이 바뀌었으면 409 SUMMARY_CHANGED")
        @NotBlank
        String version
) {

    /** 고친 본문은 승인에만, 비어 있지 않게. */
    @JsonIgnore
    @AssertTrue
    public boolean isEditedContentValid() {
        if (editedContent == null) {
            return true;
        }
        return "approve".equals(decision) && !editedContent.isBlank();
    }

    /** 검증을 통과한 뒤에만 부른다. */
    public Gate1Action action() {
        return Gate1Action.valueOf(decision.toUpperCase(Locale.ROOT));
    }
}
