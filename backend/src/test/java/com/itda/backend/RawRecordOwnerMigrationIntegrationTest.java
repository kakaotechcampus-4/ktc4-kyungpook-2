package com.itda.backend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.itda.backend.domain.Organization;
import com.itda.backend.domain.RawRecord;
import com.itda.backend.domain.RawRecordStatus;
import com.itda.backend.domain.User;
import com.itda.backend.exception.RawRecordNotFoundException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.OrganizationRepository;
import com.itda.backend.repository.RawRecordRepository;
import com.itda.backend.repository.UserRepository;
import com.itda.backend.service.RawRecordService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 psql 진입 스크립트의 검증 모드와 적용 모드를 모두 실행한다. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class RawRecordOwnerMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("itda_migration_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    }

    @Autowired private RawRecordRepository records;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private RawRecordService service;
    @TempDir Path directory;

    private Organization ownerOrganization;
    private Organization otherOrganization;
    private User owner;
    private User otherUser;
    private RawRecord first;
    private RawRecord second;
    private String legacyId;

    @BeforeEach
    void prepare() {
        records.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        ownerOrganization = organizations.saveAndFlush(OrganizationFixture.center());
        otherOrganization = organizations.saveAndFlush(OrganizationFixture.center());
        // 다른 기관 ID와 같은 숫자인 카카오 ID도 기록별 매핑으로 이전한다.
        legacyId = String.valueOf(otherOrganization.getId());
        owner = users.saveAndFlush(UserFixture.organizationUser(legacyId, "소유자", ownerOrganization.getId()));
        otherUser = users.saveAndFlush(UserFixture.organizationUser("other", "다른 기관", otherOrganization.getId()));
        first = records.saveAndFlush(record("one.pdf"));
        second = records.saveAndFlush(record("two.pdf"));
        first = records.findById(first.getId()).orElseThrow();
        second = records.findById(second.getId()).orElseThrow();
        for (String script : List.of("migrate-raw-record-owners.sql", "validate-raw-record-owners.sql")) {
            postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts", script).toAbsolutePath()),
                    "/tmp/" + script);
        }
    }

    private RawRecord record(String name) {
        return new RawRecord(legacyId, name, "kept/" + name, "application/pdf", 12, RawRecordStatus.PENDING);
    }

    private String row(RawRecord record) {
        return record.getId() + "," + legacyId + "," + ownerOrganization.getId() + "\n";
    }

    private org.testcontainers.containers.Container.ExecResult run(String rows, boolean apply) throws Exception {
        Path csv = directory.resolve("raw-record-owner-mapping.csv");
        Files.writeString(csv, "raw_record_id,legacy_kakao_id,organization_id\n" + rows, StandardCharsets.UTF_8);
        postgres.copyFileToContainer(MountableFile.forHostPath(csv), "/tmp/raw-record-owner-mapping.csv");
        return postgres.execInContainer("sh", "-c", "cd /tmp && psql -U test -d itda_migration_test -v apply="
                + apply + " -f migrate-raw-record-owners.sql");
    }

    private void assertOwnersUnchanged() {
        assertThat(records.findById(first.getId()).orElseThrow().getInstitutionId()).isEqualTo(legacyId);
        assertThat(records.findById(second.getId()).orElseThrow().getInstitutionId()).isEqualTo(legacyId);
    }

    @Test
    void dryRunShowsPlanAndRollsBack() throws Exception {
        var result = run(row(first) + row(second), false);
        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        assertThat(result.getStdout()).contains("records_to_migrate", "ROLLBACK");
        assertOwnersUnchanged();
    }

    @Test
    void applyPreservesRecordsAndRestrictsReadsToTheirNewOwner() throws Exception {
        var result = run(row(first) + row(second), true);
        assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
        RawRecord moved = service.getById(first.getId(), String.valueOf(owner.getId()));
        assertThat(moved.getInstitutionId()).isEqualTo(String.valueOf(ownerOrganization.getId()));
        assertThat(moved.getStoredPath()).isEqualTo(first.getStoredPath());
        assertThat(moved.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(moved.getOriginalFilename()).isEqualTo(first.getOriginalFilename());
        assertThat(service.getByInstitution(String.valueOf(owner.getId()))).hasSize(2);
        assertThat(service.getByInstitution(String.valueOf(otherUser.getId()))).isEmpty();
        assertThatThrownBy(() -> service.getById(first.getId(), String.valueOf(otherUser.getId())))
                .isInstanceOf(RawRecordNotFoundException.class);
        assertThat(records.count()).isEqualTo(2);
    }

    @Test
    void invalidMappingsNeverPartiallyApply() throws Exception {
        List<String> invalid = List.of(
                row(first), // 누락
                row(first) + row(first) + row(second), // 중복
                row(first) + second.getId() + ",wrong," + ownerOrganization.getId() + "\n", // 소유자 불일치
                row(first) + second.getId() + "," + legacyId + ",99999999\n", // 없는 기관
                row(first) + second.getId() + "," + legacyId + "," + otherOrganization.getId() + "\n", // 다른 소속
                row(first) + row(second) + "99999999," + legacyId + "," + ownerOrganization.getId() + "\n",
                "");
        for (String mapping : invalid) {
            var result = run(mapping, true);
            assertThat(result.getExitCode()).as("mapping: %s", mapping).isNotZero();
            assertOwnersUnchanged();
        }
    }

    @Test
    void deletedOwnerCannotBeMigrated() throws Exception {
        owner.delete();
        users.saveAndFlush(owner);
        var result = run(row(first) + row(second), true);
        assertThat(result.getExitCode()).isNotZero();
        assertOwnersUnchanged();
    }
}
