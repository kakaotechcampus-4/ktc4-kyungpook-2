package com.itda.backend.dto.response.consent;

import java.util.List;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentState;

/**
 * G-41 결과. 화면이 아이 목록을 다시 부르지 않고 상태를 고칠 수 있게 바뀐 뒤의 상태를 돌려준다.
 *
 * @param childStatus   아이 전체 요약 상태 (어느 기관이든 동의가 있으면 active)
 * @param consent       이 기관에 대한 동의 상태
 * @param allowedFields 이 기관에 지금 공유되는 항목. granted 가 아니면 빈 배열
 */
public record ConsentResponse(ChildStatus childStatus, ConsentState consent, List<ConsentField> allowedFields) {
}
