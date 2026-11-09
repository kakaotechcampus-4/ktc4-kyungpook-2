package com.itda.backend.dto.response.organization;

public record MyInstitutionResponse(String id, String name, String type, boolean verified, String code) {

    public static MyInstitutionResponse of(InstitutionResponse institution, String code) {
        return new MyInstitutionResponse(
                institution.id(), institution.name(), institution.type(), institution.verified(), code);
    }
}
