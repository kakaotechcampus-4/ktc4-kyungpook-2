package com.itda.backend.dto.request.child;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 보호자가 기관 코드로 아이를 그 기관에 연결해 달라고 요청한다. 공백·하이픈·대소문자는 서버가 정리한다.
 */
public record RequestInstitutionLinkRequest(
        @Schema(example = "ABCD-EFGH")
        @NotBlank
        String code
) {
}
