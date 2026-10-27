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

/**
 * POST /api/v1/summaries/{summaryId}/gate1 의 요청. 교사의 Gate 1 결정이다.
 *
 * <p>{@code cards} 는 승인할 때만 보낸다 — 요약 문장(claims)마다 유지·수정·삭제와 새 문장 추가다 (#221). 없으면 그대로
 * 승인이다. 보류·제거·확인·되돌리기에 보내면 버려지므로 400 으로 막는다. 원래 문장을 모두 한 번씩 보냈는지는 요약을 봐야 알아서 서비스가
 * 본다. {@code version} 은 목록에서 받은 값 그대로다. 여기서 막는 형식 오류는 400 INVALID_REQUEST, 서비스가 보는 번호
 * 오류는 400 SUMMARY_INVALID_REQUEST 다.
 */
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

        // 옛 계약(#221 전)의 고친 본문. 모르는 필드는 조용히 버려지는데, 버리면 고친 글 대신 AI 원문이 승인된다.
        // 승인은 되돌릴 수 없어 400 으로 막는다. 명세에는 싣지 않는다.
        @Schema(hidden = true)
        String editedContent
) {

    /** 옛 계약의 고친 본문은 받지 않는다 — 문장 카드로 보낸다. */
    @JsonIgnore
    @AssertTrue
    public boolean isEditedContentAbsent() {
        return editedContent == null;
    }

    /** 요약 한 편의 문장 수와 문장 길이 상한. AI 요약은 하루치 일지라 이보다 훨씬 작다 — 잘못된 요청만 막는다. */
    public static final int MAX_CARDS = 100;
    public static final int MAX_TEXT_LENGTH = 1000;

    /** 카드는 승인에만, 비어 있지 않게. */
    @JsonIgnore
    @AssertTrue
    public boolean isCardsValid() {
        if (cards == null) {
            return true;
        }
        return "approve".equals(decision) && !cards.isEmpty();
    }

    /** 검증을 통과한 뒤에만 부른다. */
    public Gate1Action action() {
        return Gate1Action.valueOf(decision.toUpperCase(Locale.ROOT));
    }

    /**
     * 문장 카드 하나. KEEP·DELETE 는 원래 문장 번호만, EDIT 은 번호와 고친 글, ADD 는 새 글만 담는다.
     *
     * @param index O-30 {@code claims} 의 {@code index}. ADD 는 원래 문장이 없어 비운다
     */
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

        /** 동작마다 필요한 값만 있게. action 이 틀리면 {@code @Pattern} 이 잡으므로 여기서는 통과시킨다. */
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

        /** 검증을 통과한 뒤에만 부른다. */
        public Gate1CardAction cardAction() {
            return Gate1CardAction.valueOf(action);
        }
    }
}
