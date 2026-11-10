package com.itda.backend.dto.response.user;

public record WithdrawalResponse(String withdrawal) {

    public static WithdrawalResponse inProgress() {
        return new WithdrawalResponse(CurrentUserResponse.WITHDRAWAL_IN_PROGRESS);
    }
}
