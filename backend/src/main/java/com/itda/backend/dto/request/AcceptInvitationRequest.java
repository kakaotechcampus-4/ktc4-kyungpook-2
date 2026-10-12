package com.itda.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * G-04 초대 코드 수락. 하이픈·공백·소문자가 섞여도 서버가 맞춘다. 형식이 틀린 코드는 400 INVALID_INVITE_CODE 로,
 * 없는 코드와 구분하지 않는다.
 */
public record AcceptInvitationRequest(@NotBlank @Size(max = 50) String code) {
}
