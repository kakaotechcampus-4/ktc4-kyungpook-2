package com.itda.backend.dto;

// frontend/app/lib/types.ts의 RawRecord와 대응. type(기록유형)은 recordType 값 목록이
// 아직 팀에서 안 정해져서 뺐다 — 정해지면 채운다.
//
// fullContent: 코드리뷰 반영(멘토 PR #86, #111) — 목록의 preview(60자)만으로는 선생님이
// 매칭을 확정하기 전에 봐야 할 아이 이름·AI 판단 근거가 본문 뒷부분에 있으면 놓친다.
// 목록 미리보기(preview)는 그대로 두고, 아이를 선택할 때 펼쳐 볼 전체 본문을 별도
// 필드로 추가한다. evidence의 {start,end}는 이 필드 기준 유니코드 코드포인트 좌표다.
public record RecordResponse(String id, String fileName, String preview, String capturedAt, String fullContent) {

    private static final int PREVIEW_LENGTH = 60;

    /**
     * 기록 본문으로 목록용 미리보기와 전체 본문을 함께 채운다.
     *
     * <p>매칭 확인 큐와 수정 요청 큐가 같은 규칙을 써야 해서 여기 모아둔다 — 자르는 길이가
     * 두 화면에서 갈라지면 같은 기록이 큐마다 다르게 보인다.
     */
    public static RecordResponse of(String id, String fileName, String content, String capturedAt) {
        return new RecordResponse(id, fileName, preview(content), capturedAt, content);
    }

    private static String preview(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH) + "…";
    }
}
