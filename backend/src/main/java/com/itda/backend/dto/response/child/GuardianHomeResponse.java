package com.itda.backend.dto.response.child;

import java.util.List;

/**
 * G-11 보호자 홈. 아이 하나와 그 아이의 최근 공유 활동이다.
 *
 * @param activity 승인된 일지마다 한 줄, 승인 최근 순 5건. 지금은 일지 도착 한 종류뿐이다
 */
public record GuardianHomeResponse(GuardianChildResponse child, List<ParentActivityResponse> activity) {
}
