package com.itda.backend.dto.request.checklist;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SendChecklistRequest(
        @ArraySchema(
                arraySchema = @Schema(description = "준비물 · 확인사항 줄. 1~10줄"),
                schema = @Schema(example = "실내화를 새로 준비해주세요.", maxLength = 100))
        @NotEmpty
        @Size(max = 10)
        List<@NotBlank @Size(max = 100) String> items,

        @ArraySchema(
                arraySchema = @Schema(description = "받을 아이 id. 우리 기관 명부에 있는 아이만. \"명부 전체\" 도 화면이 id 를 모두 보낸다"),
                schema = @Schema(type = "string", example = "12"))
        @NotEmpty
        List<@NotNull Long> childIds
) {

    public SendChecklistRequest {
        items = (items == null) ? null : items.stream().map(i -> i == null ? null : i.strip()).toList();
        childIds = (childIds == null) ? null : childIds.stream().distinct().toList();
    }
}
