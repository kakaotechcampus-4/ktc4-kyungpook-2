package com.itda.backend.dto;

import com.itda.backend.domain.ChildStatus;

// frontend/app/lib/types.ts의 Child 중 RosterPicker.tsx가 실제로 쓰는 필드
// (id/name/birthDate/status)와 기관 관리번호(externalId, 없으면 null). school/institutions/care는
// 이번 범위 밖 — Child 엔티티 자체에 아직 없다.
public record ChildRosterResponse(String id, String name, String birthDate, String externalId, ChildStatus status) {
}
