package com.itda.backend.dto.request.user;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.itda.backend.domain.organization.OrganizationType;

/**
 * POST /api/v1/auth/signup 의 요청. 카카오 로그인 뒤 1회성 가입 화면에서 보낸다.
 *
 * <p>보호자는 {@code role} 만 보낸다 — 기관 필드가 하나라도 오면 400 이다. 조용히 무시하면
 * "기관 정보를 보냈는데 기관이 안 생겼다" 는 착각이 생긴다. 기관은 기관명·유형·사업자등록번호가
 * 모두 필요하다. 역할에 따라 규칙이 달라서 필드 애너테이션만으로는 표현이 안 되므로
 * {@link #isOrganizationInfoMatchingRole()} 로 교차 검증한다. 실패는 전부 400 INVALID_REQUEST 다.
 *
 * <p>담당자가 바뀌는 기관은 기관 정보 대신 {@code handoverCode}(이전 담당자가 발급한 인계 코드)만 보낸다 (#478).
 *
 * <p>{@code organizationType} 이 enum 에 없는 값이면 역직렬화 단계에서 실패하고,
 * 전역 예외 처리기가 같은 400 INVALID_REQUEST 로 바꾼다.
 */
public record SignupRequest(
        @NotNull
        @Pattern(regexp = "org|parent")
        String role,

        @Size(max = 100)
        String organizationName,

        OrganizationType organizationType,

        /* 형식만 본다. 체크섬·진위는 검증하지 않는다(테스트 데이터를 쉽게 넣기 위한 의도적 선택). */
        @Pattern(regexp = "^\\d{10}$")
        String businessNumber,

        /*
         * 기관 담당자 인계 코드 (#478). 있으면 기관을 새로 만들지 않고 코드를 발급한 담당자의 기관에 붙는다. 형식은 서비스가
         * 본다 — 형식 오류도 다른 쓸 수 없는 코드와 같은 400 INVALID_HANDOVER_CODE 로 답해야 해서다.
         */
        @Size(max = 20)
        String handoverCode
) {

    public static final String ROLE_ORGANIZATION = "org";

    /*
     * 아래 두 메서드는 is 로 시작해 getter 처럼 보인다. @JsonIgnore 가 없으면 Swagger 요청 스키마에
     * organizationSignup · organizationInfoMatchingRole 이라는 없는 필드로 나타난다.
     */
    @JsonIgnore
    public boolean isOrganizationSignup() {
        return ROLE_ORGANIZATION.equals(role);
    }

    /** 기관 담당자 인계 코드로 가입한다 — 기관을 새로 만들지 않는다. */
    @JsonIgnore
    public boolean isHandoverSignup() {
        return isOrganizationSignup() && handoverCode != null;
    }

    /**
     * 기관이면 세 필드가 모두 있거나(새 기관) 인계 코드만 있어야 하고(이어받기), 보호자면 아무것도 없어야 한다.
     * 섞어 보내면 어느 쪽인지 정할 수 없어 400 이다.
     */
    @JsonIgnore
    @AssertTrue
    public boolean isOrganizationInfoMatchingRole() {
        boolean noOrganizationInfo = organizationName == null && organizationType == null && businessNumber == null;
        if (isHandoverSignup()) {
            return noOrganizationInfo && !handoverCode.isBlank();
        }
        if (isOrganizationSignup()) {
            return organizationName != null && !organizationName.isBlank()
                    && organizationType != null
                    && businessNumber != null;
        }
        return noOrganizationInfo && handoverCode == null;
    }
}
