package com.itda.backend.dto.response.organization;

import java.time.OffsetDateTime;

/**
 * 새로 발급한 기관 담당자 인계 코드 (#478).
 *
 * <p>{@code code} 는 하이픈 없는 8자다. {@code XXXX-XXXX} 표시는 화면이 한다. {@code expiresAt} 은 시간대 오프셋을 함께 보낸다.
 */
public record IssuedHandoverResponse(String code, OffsetDateTime expiresAt) {
}
