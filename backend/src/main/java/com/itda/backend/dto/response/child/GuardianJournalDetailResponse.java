package com.itda.backend.dto.response.child;

import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * 보호자 일지 상세 (api-spec G-21). 목록 원소({@link GuardianJournalResponse})에 옆 일지를 더한 것이다 (#458).
 * 화면이 이전 · 다음 버튼과 "같은 날 다른 곳에서는" 을 그리려고 목록 전체를 또 받지 않게 한다.
 *
 * <p>옆 일지는 목록(G-20)과 같은 순서 — 관찰 날짜 최근 순, 같은 날은 승인 최근 순 — 로 정한다.
 *
 * @param prevId  목록에서 바로 뒤 칸, 곧 더 예전 일지. 맨 끝이면 {@code null}
 * @param nextId  목록에서 바로 앞 칸, 곧 더 최근 일지. 맨 앞이면 {@code null}
 * @param sameDay 같은 관찰 날짜에 <b>다른 기관</b>이 쓴 일지, 목록 순서. 기관은 id 로 가른다. 없으면 빈 배열
 */
public record GuardianJournalDetailResponse(
        String id,
        String childId,
        InstitutionResponse institution,
        String date,
        boolean isNew,
        String content,
        String prevId,
        String nextId,
        List<GuardianJournalResponse> sameDay) {

    public static GuardianJournalDetailResponse of(GuardianJournalResponse entry, String prevId, String nextId,
            List<GuardianJournalResponse> sameDay) {
        return new GuardianJournalDetailResponse(entry.id(), entry.childId(), entry.institution(), entry.date(),
                entry.isNew(), entry.content(), prevId, nextId, sameDay);
    }
}
