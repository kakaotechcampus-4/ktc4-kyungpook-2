package com.itda.backend.dto.response.consent;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * G-40 대기 중 기관 권한 요청 한 줄. 아이가 명부에 올라 있는데 아직 아무도 동의하지 않은 기관이다.
 * 보호자 화면의 알림 점·홈 배너·알림 화면이 이 목록 하나를 본다.
 */
public record PendingConsentRequestResponse(InstitutionResponse institution) {
}
