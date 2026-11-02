package com.itda.backend.dto.request.child;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 기관이 보호자의 연결 요청을 수락한다. 아이 등록과 같이 관리번호를 필수로 받는다 — 요청으로 들어온 아이만
 * 번호 없이 명부에 남지 않도록.
 */
public record AcceptInstitutionLinkRequest(
        @Schema(example = "2026-0031")
        @NotBlank
        @Size(max = 50)
        String externalId
) {

    public AcceptInstitutionLinkRequest {
        externalId = (externalId == null) ? null : externalId.strip();
    }
}
