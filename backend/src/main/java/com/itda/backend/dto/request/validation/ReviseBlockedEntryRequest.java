package com.itda.backend.dto.request.validation;

import com.itda.backend.domain.record.JournalEntry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 검증에서 막힌 일지의 고친 본문 (#357, docs/api/api-spec.md O-25). 고치지 않은 본문인지는 서비스가 확인한다.
 *
 * @param content 고친 본문 전체. 고친 본문은 그대로 검증 AI 에 가므로 업로드와 같은 길이({@link JournalEntry#MAX_CONTENT_LENGTH})까지만 받는다 (#424)
 */
public record ReviseBlockedEntryRequest(
        @NotBlank
        @Size(max = JournalEntry.MAX_CONTENT_LENGTH)
        String content) {
}
