package com.itda.backend.dto.response.child;

import com.itda.backend.domain.child.ChildStatus;
import com.itda.backend.domain.consent.ConsentState;

// frontend/app/lib/types.ts의 Child 중 RosterPicker.tsx가 실제로 쓰는 필드
// (id/name/birthDate/status)와 기관 관리번호(externalId, 없으면 null). school/institutions/care는
// 이번 범위 밖 — Child 엔티티 자체에 아직 없다.
//
// status 는 아이 전체 요약이라 어느 기관이든 동의가 있으면 active 다. 학교에 동의한 아이가 센터 명부에 막 올라왔을 때
// 센터 화면이 status 를 보면 "이용 중" 으로 잘못 보인다. 기관 화면은 이 기관에 대한 동의인 consent 로 판단한다.
public record ChildRosterResponse(
        String id, String name, String birthDate, String externalId, ChildStatus status, ConsentState consent) {
}
