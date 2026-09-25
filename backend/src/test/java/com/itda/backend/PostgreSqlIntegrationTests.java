package com.itda.backend;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Tag("integration")
@ActiveProfiles("test")
class PostgreSqlIntegrationTests {

	@Container
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("itda_test")
			.withUsername("test")
			.withPassword("test");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Test
	void contextLoadsWithPostgreSql() {
	}

	/**
	 * users 는 PostgreSQL 예약어(USER)를 피한 이름이다. 테이블이 실제로 만들어지고
	 * enum 이 문자열로 저장되는지 PostgreSQL 에서 확인한다.
	 */
	@Test
	void userTableIsCreatedAndEnumsAreStoredAsStrings() {
		User saved = userRepository.save(UserFixture.parent("pg-1", "박지현"));

		assertThat(userRepository.findByKakaoId("pg-1")).isPresent();
		assertThat(saved.getRole()).isEqualTo(UserRole.PARENT);
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	/** 유니크 제약은 엔티티를 만들 때 넣지 않으면 ddl-auto: update 가 나중에 붙여주지 않는다. */
	@Test
	void duplicateKakaoIdIsRejectedByTheUniqueConstraint() {
		userRepository.saveAndFlush(User.pending("pg-dup", "첫번째"));

		assertThatThrownBy(() -> userRepository.saveAndFlush(
				User.pending("pg-dup", "두번째")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 사업자등록번호 유니크 제약이 PostgreSQL 에서도 실제로 걸린다. */
	@Test
	void duplicateBusinessNumberIsRejectedByTheUniqueConstraint() {
		organizationRepository.saveAndFlush(Organization.of("햇살센터", OrganizationType.CENTER, "1234567890"));

		assertThatThrownBy(() -> organizationRepository.saveAndFlush(
				Organization.of("다른센터", OrganizationType.SCHOOL, "1234567890")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
