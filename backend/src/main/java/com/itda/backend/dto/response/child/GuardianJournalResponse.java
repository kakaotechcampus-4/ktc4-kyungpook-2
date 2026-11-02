package com.itda.backend.dto.response.child;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자 일지 한 건 (api-spec G-20·G-21). 교사가 Gate 1 에서 승인한 요약 하나다.
 *
 * <p>관찰 시각·태그·사진·기관 메모는 없다. 요약은 하루 단위라 시각이 없고, 나머지는 저장하는 곳이 없다.
 *
 * @param id      승인본({@code child_context}) id
 * @param date    관찰 날짜 {@code yyyy-MM-dd}
 * @param isNew   승인된 지 24시간이 안 됐다. 읽었는지와는 무관하다
 * @param content 교사가 승인한 최종본. 고쳐서 승인했다면 고친 글이다
 */
public record GuardianJournalResponse(
        String id, String childId, InstitutionResponse institution, String date, boolean isNew, String content) {
}
