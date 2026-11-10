package com.itda.backend.dto.request.summary;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.itda.backend.domain.approval.Gate1Action;
import com.itda.backend.domain.approval.Gate1CardAction;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Gate1DecisionRequest(
        @Schema(description = "confirm·revert 는 요약할 내용 없음(noContent) 요약에만, 나머지는 그 밖의 요약에만 받는다",
                allowableValues = {"approve", "hold", "remove", "confirm", "revert"}, example = "approve")
        @NotNull
        @Pattern(regexp = "^(approve|hold|remove|confirm|revert)$")
        String decision,

        @Schema(description = "문장 카드. 승인할 때만 보내고, 원래 문장(index)은 모두 한 번씩 담는다. 배열 순서가 최종 순서다. "
                + "없으면 그대로 승인", nullable = true)
        @Size(max = MAX_CARDS)
        List<@Valid @NotNull Card> cards,

        @Schema(description = "목록에서 받은 version 그대로. 그 사이 요약이 바뀌었으면 409 SUMMARY_CHANGED")
        @NotBlank
        String version,

        @Schema(hidden = true)
        String editedContent
) {

    @JsonIgnore
    @AssertTrue
    public boolean isEditedContentAbsent() {
        return editedContent == null;
    }

    public static final int MAX_CARDS = 100;
    public static final int MAX_TEXT_LENGTH = 1000;

    @JsonIgnore
    @AssertTrue
    public boolean isCardsValid() {
        if (cards == null) {
            return true;
        }
        return "approve".equals(decision) && !cards.isEmpty();
    }

    public Gate1Action action() {
        return Gate1Action.valueOf(decision.toUpperCase(Locale.ROOT));
    }

    public record Card(
            @Schema(description = "원래 문장 번호. ADD 는 비운다", nullable = true, example = "0")
            Integer index,

            @Schema(allowableValues = {"KEEP", "EDIT", "DELETE", "ADD"}, example = "KEEP")
            @NotNull
            @Pattern(regexp = "^(KEEP|EDIT|DELETE|ADD)$")
            String action,

            @Schema(description = "EDIT 은 고친 글, ADD 는 새 글. KEEP·DELETE 는 비운다", nullable = true)
            @Size(max = MAX_TEXT_LENGTH)
            String text
    ) {

        @JsonIgnore
        @AssertTrue
        public boolean isShapeValid() {
            if (action == null) {
                return true;
            }
            boolean hasIndex = index != null && index >= 0;
            boolean hasText = text != null && !text.isBlank();
            return switch (action) {
                case "KEEP", "DELETE" -> hasIndex && text == null;
                case "EDIT" -> hasIndex && hasText;
                case "ADD" -> index == null && hasText;
                default -> true;
            };
        }

        public Gate1CardAction cardAction() {
            return Gate1CardAction.valueOf(action);
        }
    }
}
