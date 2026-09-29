package com.itda.backend.dto;

import com.itda.backend.domain.ChildStatus;

// frontend/app/lib/types.ts의 Child 중 RosterPicker.tsx가 실제로 쓰는 필드만
// (id/name/birthDate/status). school/institutions/care는 이번 범위 밖 — Child
// 엔티티 자체에 아직 없다.
public record ChildRosterResponse(String id, String name, String birthDate, ChildStatus status) {
}
