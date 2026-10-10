package com.itda.backend.dto;

// frontend/app/lib/types.ts의 BlockedItem과 대응 (docs/api/api-spec.md O-24).
//
// violationReason은 화면에 그대로 노출되므로 선생님이 읽고 바로 조치할 수 있는 한국어 문장이다.
// 코드로 분기해야 하는 화면을 위해 violationCode(AI가 보낸 issue_types 원본)도 같이 내려준다.
public record ValidationQueueItemResponse(
        String id,
        Long journalEntryId,
        RecordResponse record,
        String childName,
        String violationReason,
        java.util.List<String> violationCode) {
}
