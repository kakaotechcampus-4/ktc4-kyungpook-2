package com.itda.backend.dto.response.checklist;

import java.time.LocalDateTime;
import java.util.List;

import com.itda.backend.dto.response.organization.InstitutionResponse;

/**
 * G-50 · G-51 보호자가 받은 준비물·확인사항 한 건.
 *
 * @param status          {@code needs_check} · {@code confirmed}. 이 아이의 보호자 중 한 명이 확인하면 모두에게 confirmed 다
 * @param confirmedAt     확인한 시각. 아직이면 {@code null}
 * @param confirmedByName 확인한 보호자의 카카오 닉네임. 아직이거나 닉네임이 없으면 {@code null} — 화면은 "다른 보호자" 로 쓴다
 */
public record GuardianChecklistResponse(
        String id,
        InstitutionResponse institution,
        List<String> items,
        LocalDateTime sentAt,
        String status,
        LocalDateTime confirmedAt,
        String confirmedByName) {
}
