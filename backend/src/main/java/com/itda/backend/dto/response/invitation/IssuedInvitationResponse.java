package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

/**
 * 새로 발급한 초대 코드. O-11 아이 등록과 O-15 추가 발급이 쓴다.
 *
 * <p>{@code code} 는 하이픈 없는 8자다. {@code XXXX-XXXX} 표시는 화면이 한다. {@code expiresAt} 은 브라우저가
 * 10분 카운트다운을 계산하므로 시간대 오프셋을 함께 보낸다.
 */
public record IssuedInvitationResponse(String id, String code, OffsetDateTime expiresAt) {
}
