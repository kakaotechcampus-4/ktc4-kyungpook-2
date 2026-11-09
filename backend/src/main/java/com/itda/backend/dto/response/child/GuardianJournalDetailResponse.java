package com.itda.backend.dto.response.child;

import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

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
