package com.itda.backend.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrganizationTest {

    @Test
    void organizationKeepsItsBusinessNumber() {
        Organization organization = Organization.of("햇살아동발달센터", OrganizationType.CENTER, "1234567890");

        assertThat(organization.getName()).isEqualTo("햇살아동발달센터");
        assertThat(organization.getType()).isEqualTo(OrganizationType.CENTER);
        assertThat(organization.getBusinessNumber()).isEqualTo("1234567890");
    }

    /** 요청 DTO 가 형식을 검사하지만, 엔티티도 숫자 10자리가 아닌 번호로는 만들어지지 않는다. */
    @Test
    void businessNumberMustBeTenDigits() {
        assertThatThrownBy(() -> Organization.of("센터", OrganizationType.CENTER, "123456789"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Organization.of("센터", OrganizationType.CENTER, "123-45-6789"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Organization.of("센터", OrganizationType.CENTER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nameAndTypeAreRequired() {
        assertThatThrownBy(() -> Organization.of(" ", OrganizationType.CENTER, "1234567890"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Organization.of("센터", null, "1234567890"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 기관 코드는 한 번만 붙는다. 보호자에게 알려 준 코드가 바뀌면 그 코드로 더 이상 찾을 수 없다. */
    @Test
    void institutionCodeIsAssignedOnce() {
        Organization organization = Organization.of("센터", OrganizationType.CENTER, "1234567890");
        assertThat(organization.getCode()).isNull();

        organization.assignCode("ABCDEFGH");

        assertThat(organization.getCode()).isEqualTo("ABCDEFGH");
        assertThatThrownBy(() -> organization.assignCode("HGFEDCBA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void institutionCodeMustBeEightCharacters() {
        Organization organization = Organization.of("센터", OrganizationType.CENTER, "1234567890");

        assertThatThrownBy(() -> organization.assignCode("ABC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> organization.assignCode(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
