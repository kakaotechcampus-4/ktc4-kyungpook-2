package com.itda.backend.service.child;

import java.time.LocalDateTime;

import com.itda.backend.dto.response.child.ParentActivityResponse;

/**
 * 홈 "최근 공유 활동" 한 줄과 그 정렬 시각. 일지 줄과 인사이트 줄을 시각으로 섞으려고 서비스 사이에서만 쓴다 —
 * 응답에는 {@code line} 만 나간다. 줄의 {@code at} 은 화면 문구라 정렬에 쓸 수 없다.
 */
public record ActivityLine(LocalDateTime at, ParentActivityResponse line) {
}
