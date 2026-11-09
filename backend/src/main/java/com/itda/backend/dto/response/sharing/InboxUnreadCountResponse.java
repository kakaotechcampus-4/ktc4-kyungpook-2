package com.itda.backend.dto.response.sharing;

/**
 * 기관 수신함에서 아직 안 연 글의 수 (#456). 기관 화면이 10초마다 묻기 때문에 목록 대신 숫자 하나만 준다.
 *
 * @param unread 목록({@link InboxItemResponse})에서 {@code readAt} 이 {@code null} 인 줄의 수
 */
public record InboxUnreadCountResponse(int unread) {
}
