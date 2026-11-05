package com.itda.backend.dto.request.validation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 검증에서 막힌 일지의 고친 본문 (#357, docs/api/api-spec.md O-25). 고치지 않은 본문인지는 서비스가 확인한다.
 *
 * @param content 고친 본문 전체. 일지 하나라 길지 않지만 상한을 둔다
 */
public record ReviseBlockedEntryRequest(
        @NotBlank
        @Size(max = MAX_CONTENT_LENGTH)
        String content) {

    public static final int MAX_CONTENT_LENGTH = 10_000;
}
