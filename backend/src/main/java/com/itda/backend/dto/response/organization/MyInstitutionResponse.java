package com.itda.backend.dto.response.organization;

/**
 * 기관 담당자가 보는 내 기관 정보. 공통 {@code Institution} 모델에 기관 코드를 더했다 —
 * 코드는 보호자에게 알려 주라고 기관에게만 보여 준다.
 *
 * @param code 기관 코드 8자리. 화면은 {@code XXXX-XXXX} 로 보여 준다
 */
public record MyInstitutionResponse(String id, String name, String type, boolean verified, String code) {

    public static MyInstitutionResponse of(InstitutionResponse institution, String code) {
        return new MyInstitutionResponse(
                institution.id(), institution.name(), institution.type(), institution.verified(), code);
    }
}
