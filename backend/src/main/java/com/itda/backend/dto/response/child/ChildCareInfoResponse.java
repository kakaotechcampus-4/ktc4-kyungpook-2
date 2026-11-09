package com.itda.backend.dto.response.child;

import java.util.List;

import com.itda.backend.domain.child.ChildCareInfo;

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

    public record MedicationResponse(String name, String time) {
    }
}
