package com.itda.backend.dto;

// frontend/app/lib/types.ts의 RawRecord와 대응. type(기록유형)은 recordType 값 목록이
// 아직 팀에서 안 정해져서 뺐다 — 정해지면 채운다.
//
// fullContent: 코드리뷰 반영(멘토 PR #86, #111) — 목록의 preview(60자)만으로는 선생님이
// 매칭을 확정하기 전에 봐야 할 아이 이름·AI 판단 근거가 본문 뒷부분에 있으면 놓친다.
// 목록 미리보기(preview)는 그대로 두고, 아이를 선택할 때 펼쳐 볼 전체 본문을 별도
// 필드로 추가한다. evidence의 {start,end}는 이 필드 기준 유니코드 코드포인트 좌표다.
public record RecordResponse(String id, String fileName, String preview, String capturedAt, String fullContent) {
}
