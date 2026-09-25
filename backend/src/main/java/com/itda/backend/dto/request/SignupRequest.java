package com.itda.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.itda.backend.domain.OrganizationType;

/**
 * POST /api/v1/auth/signup 의 요청. 카카오 로그인 뒤 1회성 가입 화면에서 보낸다.
 *
 * <p>보호자는 {@code role} 만 보낸다. 기관은 기관명·유형·사업자등록번호가 모두 필요하다.
 * 역할에 따라 필수 여부가 달라서 필드 애너테이션만으로는 표현이 안 되므로
 * {@link #isOrganizationInfoPresent()} 로 교차 검증한다. 실패는 전부 400 INVALID_REQUEST 다.
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
        String businessNumber
) {

    public static final String ROLE_ORGANIZATION = "org";

    /*
     * 아래 두 메서드는 is 로 시작해 getter 처럼 보인다. @JsonIgnore 가 없으면 Swagger 요청 스키마에
     * organizationSignup · organizationInfoPresent 라는 없는 필드로 나타난다.
     */
    @JsonIgnore
    public boolean isOrganizationSignup() {
        return ROLE_ORGANIZATION.equals(role);
    }

    @JsonIgnore
    @AssertTrue
    public boolean isOrganizationInfoPresent() {
        if (!isOrganizationSignup()) {
            return true;
        }
        return organizationName != null && !organizationName.isBlank()
                && organizationType != null
                && businessNumber != null;
    }
}
