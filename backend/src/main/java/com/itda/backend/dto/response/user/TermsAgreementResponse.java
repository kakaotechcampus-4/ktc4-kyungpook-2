package com.itda.backend.dto.response.user;

/** POST /api/v1/auth/terms 의 응답. 성공하면 늘 true 다 — 프론트가 다음 화면을 정할 때 /auth/me 를 다시 묻지 않게 한다. */
public record TermsAgreementResponse(boolean termsAgreed) {
}
