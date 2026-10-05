package com.itda.backend.dto;

// frontend/app/lib/types.ts의 RawRecord와 대응. type(기록유형)은 recordType 값 목록이
// 아직 팀에서 안 정해져서 뺐다 — 정해지면 채운다.
//
// preview: 코드리뷰 반영(멘토 PR #86) — 매칭 확인 화면에서 선생님이 누구의 기록인지 판단해야
// 하므로 더 이상 앞부분만 자르지 않고 기록 본문 전체를 담는다. 필드 이름은 호환을 위해 유지한다.
public record RecordResponse(String id, String fileName, String preview, String capturedAt) {
}
