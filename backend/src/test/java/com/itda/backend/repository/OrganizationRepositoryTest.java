package com.itda.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;

@DataJpaTest
@ActiveProfiles("test")
class OrganizationRepositoryTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void 같은_사업자등록번호로는_두_기관을_만들_수_없다() {
        organizationRepository.saveAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));

        assertThatThrownBy(() -> organizationRepository.saveAndFlush(
                Organization.of("다른센터", OrganizationType.SCHOOL, "1234567890")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 같은 이름의 센터가 실제로 여러 곳 있을 수 있다. 기관을 구분하는 키는 사업자등록번호다. */
    @Test
    void 이름이_같아도_사업자등록번호가_다르면_만들_수_있다() {
        organizationRepository.saveAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));

        assertThatCode(() -> organizationRepository.saveAndFlush(
                Organization.of("햇살센터", OrganizationType.CENTER, "0987654321")))
                .doesNotThrowAnyException();
    }

    @Test
    void 사업자등록번호로_이미_가입된_기관인지_확인한다() {
        organizationRepository.saveAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));

        assertThat(organizationRepository.existsByBusinessNumber("1234567890")).isTrue();
        assertThat(organizationRepository.existsByBusinessNumber("0987654321")).isFalse();
    }
}
