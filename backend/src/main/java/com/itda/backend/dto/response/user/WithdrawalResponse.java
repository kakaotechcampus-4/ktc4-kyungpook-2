package com.itda.backend.dto.response.user;

/**
 * DELETE /api/v1/auth/me 가 202 로 접수만 했을 때의 응답. 완료는 본문 없는 204 다.
 *
 * @param withdrawal 늘 {@code in_progress}. 프론트는 탈퇴 안내 화면에서 {@code /auth/me} 를 다시 읽어 결과를 본다
 */
public record WithdrawalResponse(String withdrawal) {

    public static WithdrawalResponse inProgress() {
        return new WithdrawalResponse(CurrentUserResponse.WITHDRAWAL_IN_PROGRESS);
    }
}
