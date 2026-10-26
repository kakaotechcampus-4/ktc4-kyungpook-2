package com.itda.backend.dto.response.child;

import java.util.List;

/**
 * G-11 보호자 홈. 아이 하나와 그 아이의 최근 공유 활동이다.
 *
 * @param activity 지금은 언제나 빈 배열이다. "기관이 무엇을 보냈다" 기록은 일지 기능(G-20·G-21)과 함께 채운다
 */
public record GuardianHomeResponse(GuardianChildResponse child, List<ParentActivityResponse> activity) {
}
