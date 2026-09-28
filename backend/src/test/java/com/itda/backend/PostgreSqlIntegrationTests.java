package com.itda.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.OrganizationType;
import com.itda.backend.domain.User;
import com.itda.backend.domain.UserRole;
import com.itda.backend.dto.request.SignupRequest;
import com.itda.backend.exception.OrganizationErrorCode;
import com.itda.backend.exception.OrganizationException;
import com.itda.backend.exception.UserErrorCode;
import com.itda.backend.exception.UserException;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.UserRepository;
import com.itda.backend.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.mockingDetails;

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
		// 컨테이너가 스키마까지 폐기하므로 컨텍스트 종료 때 다시 접속해 DROP 하지 않는다.
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
	}

	@MockitoSpyBean
	private UserRepository userRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private UserService userService;

	@Autowired
	private PlatformTransactionManager transactionManager;

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

	/*
	 * 동시 가입. H2 테스트는 요청을 순서대로 보내므로 잠금과 유니크 경합을 실제로 겪지 않는다.
	 * 여기서는 두 스레드를 같은 순간에 출발시켜 PostgreSQL 에서 확인한다.
	 */

	private static SignupRequest organizationSignup(String businessNumber) {
		return new SignupRequest("org", "햇살센터", OrganizationType.CENTER, businessNumber);
	}

	/** 작업들을 동시에 출발시키고, 각 작업이 던진 예외(성공이면 null)를 순서대로 돌려준다. */
	private static List<Throwable> runAtOnce(List<Callable<?>> tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Callable<?> task : tasks) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
			List<Throwable> failures = new ArrayList<>();
			for (Future<?> future : futures) {
				try {
					future.get(30, TimeUnit.SECONDS);
					failures.add(null);
				} catch (java.util.concurrent.ExecutionException e) {
					failures.add(e.getCause());
				}
			}
			return failures;
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * 가입 버튼을 두 번 빠르게 누른 경우. 회원 행을 잠그므로 두 번째 요청은 첫 번째가 끝날 때까지
	 * 기다렸다가 "이미 가입함" 을 보고 409 로 끝난다. 기관은 하나만 생긴다.
	 */
	@Test
	void sameUserSigningUpTwiceAtOnceCreatesOnlyOneOrganization() throws Exception {
		User user = userRepository.save(User.pending("pg-race-same", "박지현"));
		String principal = String.valueOf(user.getId());

		List<Throwable> results = runAtOnce(List.of(
				() -> userService.completeSignup(principal, organizationSignup("2000000001")),
				() -> userService.completeSignup(principal, organizationSignup("2000000002"))));

		assertThat(results).filteredOn(e -> e == null).hasSize(1);
		assertThat(results).filteredOn(e -> e != null).singleElement()
				.isInstanceOfSatisfying(UserException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ALREADY_SIGNED_UP));
		long created = List.of("2000000001", "2000000002").stream()
				.filter(organizationRepository::existsByBusinessNumber).count();
		assertThat(created).isEqualTo(1);
	}

	/**
	 * 서로 다른 두 사람이 같은 사업자등록번호로 동시에 가입한 경우. 둘 다 "아직 없는 번호" 확인을
	 * 통과하더라도 유니크 제약이 한쪽을 막고, 그 예외는 500 이 아니라 409 로 바뀐다.
	 */
	@Test
	void sameBusinessNumberAtOnceLetsOnlyOneOrganizationIn() throws Exception {
		User first = userRepository.save(User.pending("pg-race-bn-1", "첫번째"));
		User second = userRepository.save(User.pending("pg-race-bn-2", "두번째"));

		List<Throwable> results = runAtOnce(List.of(
				() -> userService.completeSignup(String.valueOf(first.getId()), organizationSignup("2000000003")),
				() -> userService.completeSignup(String.valueOf(second.getId()), organizationSignup("2000000003"))));

		assertThat(results).filteredOn(e -> e == null).hasSize(1);
		assertThat(results).filteredOn(e -> e != null).singleElement()
				.isInstanceOfSatisfying(OrganizationException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OrganizationErrorCode.DUPLICATE_BUSINESS_NUMBER));
		long signedUp = List.of(first.getId(), second.getId()).stream()
				.map(id -> userRepository.findById(id).orElseThrow())
				.filter(User::isSignupCompleted).count();
		assertThat(signedUp).isEqualTo(1);
	}

	/** 두 조회가 모두 빈 결과를 본 뒤 INSERT 하도록 해 중복 경합을 확실하게 재현한다. */
	@Test
	void simultaneousFirstLoginsBothReturnTheSameUser() throws Exception {
		CountDownLatch bothRead = new CountDownLatch(2);
		var repositoryDelegate = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			Object result = repositoryDelegate.answer(invocation);
			bothRead.countDown();
			if (!bothRead.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 최초 조회 대기 시간 초과");
			}
			return result;
		}).when(userRepository).findByKakaoIdForUpdate("pg-login-first");
		List<Long> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
		try {
			List<Throwable> results = runAtOnce(List.of(
					() -> ids.add(userService.findOrCreateByKakaoId("pg-login-first", "첫번째").getId()),
					() -> ids.add(userService.findOrCreateByKakaoId("pg-login-first", "두번째").getId())));
			assertThat(results).containsOnlyNulls();
			assertThat(ids).hasSize(2);
			assertThat(ids.get(0)).isEqualTo(ids.get(1));
			assertThat(userRepository.findAll().stream()
					.filter(u -> u.getKakaoId().equals("pg-login-first")).count()).isEqualTo(1);
		} finally {
			reset(userRepository);
		}
	}

	@Test
	void loginWaitsForSignupAndKeepsItsRoleAndOrganization() throws Exception {
		User pending = userRepository.saveAndFlush(User.pending("pg-login-signup", "옛이름"));
		CountDownLatch signupLocked = new CountDownLatch(1);
		CountDownLatch finishSignup = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> signup = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				userRepository.findActiveByIdForUpdate(pending.getId()).orElseThrow();
				signupLocked.countDown();
				try {
					if (!finishSignup.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("가입 완료 대기 시간 초과");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return userService.completeSignup(String.valueOf(pending.getId()), organizationSignup("2000000004"));
			}));
			assertThat(signupLocked.await(10, TimeUnit.SECONDS)).isTrue();
			CountDownLatch loginStarted = new CountDownLatch(1);
			Future<User> login = executor.submit(() -> {
				loginStarted.countDown();
				return userService.findOrCreateByKakaoId("pg-login-signup", "새이름");
			});
			assertThat(loginStarted.await(10, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> login.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			finishSignup.countDown();
			signup.get(10, TimeUnit.SECONDS);
			User found = login.get(10, TimeUnit.SECONDS);
			assertThat(found.getRole()).isEqualTo(UserRole.ORGANIZATION);
			assertThat(found.getOrganizationId()).isNotNull();
			User persisted = userRepository.findById(pending.getId()).orElseThrow();
			assertThat(persisted.getOrganizationId()).isEqualTo(found.getOrganizationId());
			assertThat(persisted.getRole()).isEqualTo(UserRole.ORGANIZATION);
			assertThat(persisted.getName()).isEqualTo("새이름");
		} finally {
			finishSignup.countDown();
			executor.shutdownNow();
		}
	}
}
