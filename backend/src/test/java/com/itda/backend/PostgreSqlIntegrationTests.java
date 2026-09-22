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

import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
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
		User saved = userRepository.save(User.of("pg-1", "박지현", UserRole.ORGANIZATION, null));

		assertThat(userRepository.findByKakaoId("pg-1")).isPresent();
		assertThat(saved.getRole()).isEqualTo(UserRole.ORGANIZATION);
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	/** 유니크 제약은 엔티티를 만들 때 넣지 않으면 ddl-auto: update 가 나중에 붙여주지 않는다. */
	@Test
	void duplicateKakaoIdIsRejectedByTheUniqueConstraint() {
		userRepository.saveAndFlush(User.of("pg-dup", "첫번째", UserRole.ORGANIZATION, null));

		assertThatThrownBy(() -> userRepository.saveAndFlush(
				User.of("pg-dup", "두번째", UserRole.ORGANIZATION, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 시드가 PostgreSQL 에서도 동작하고 기관 유형이 그대로 저장된다. */
	@Test
	void seededOrganizationsExistOnPostgreSql() {
		assertThat(organizationRepository.findFirstByOrderByIdAsc()).isPresent();
	}
}
