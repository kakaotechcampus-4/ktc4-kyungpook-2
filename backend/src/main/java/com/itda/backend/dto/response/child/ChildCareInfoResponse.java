package com.itda.backend.dto.response.child;

import java.util.List;

import com.itda.backend.domain.child.ChildCareInfo;

/**
 * api-spec §3 {@code Child.care} — 보호자가 적어 둔 알레르기와 복용약. 현재 이 아이와 연결된 모든 보호자가 조회·수정하고,
 * 기관에는 공유하지 않는다. 저장한 적이 없으면 빈 배열 둘이다 — 프론트 타입이 {@code null} 을 허용하지 않는다.
 */
public record ChildCareInfoResponse(List<String> allergies, List<MedicationResponse> medications) {

    public static ChildCareInfoResponse empty() {
        return new ChildCareInfoResponse(List.of(), List.of());
    }

    public static ChildCareInfoResponse from(ChildCareInfo info) {
        return new ChildCareInfoResponse(
                info.getAllergies(),
                info.getMedications().stream()
                        .map(medication -> new MedicationResponse(medication.name(), medication.time()))
                        .toList());
    }

    /** @param time 복용 시간 문구. 없으면 빈 문자열 */
    public record MedicationResponse(String name, String time) {
    }
}
