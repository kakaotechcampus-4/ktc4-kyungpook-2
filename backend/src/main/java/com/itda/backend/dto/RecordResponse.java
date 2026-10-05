package com.itda.backend.dto;

// frontend/app/lib/types.ts의 RawRecord와 대응. type(기록유형)은 recordType 값 목록이
// 아직 팀에서 안 정해져서 뺐다 — 정해지면 채운다.
public record RecordResponse(String id, String fileName, String preview, String capturedAt) {
}
