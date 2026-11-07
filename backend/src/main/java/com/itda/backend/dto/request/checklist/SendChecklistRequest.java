package com.itda.backend.dto.request.checklist;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * O-70 준비물·확인사항 보내기.
 *
 * <p>항목은 앞뒤 공백을 생성자에서 떼고 그 뒤에 검증한다({@code UpdateCareInfoRequest} 와 같은 방식). 공백뿐인 줄은
 * {@code @NotBlank} 에서 걸린다. 받을 아이는 같은 id 를 하나로 합친다. 명부에 있는지는 서비스가 본다.
 * 형식 실패는 전부 400 INVALID_REQUEST 다.
 */
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
        // null 원소는 null 로 둬 @NotBlank · @NotNull 이 잡게 한다
        items = (items == null) ? null : items.stream().map(i -> i == null ? null : i.strip()).toList();
        childIds = (childIds == null) ? null : childIds.stream().distinct().toList();
    }
}
