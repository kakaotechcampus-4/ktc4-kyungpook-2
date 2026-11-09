package com.itda.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.user.User;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.fixture.TermsFixture;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.global.jwt.JwtCookie;
import com.itda.backend.global.jwt.JwtProvider;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordUploadRepository;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.record.RawRecordUploadService;
import com.itda.backend.service.storage.RawFileStorage;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RawRecordUploadApiTest {

    private static final String UPLOAD_URL = "/api/v1/raw-records/upload-url";
    private static final byte[] NOTE = "오후 미술 활동에 끝까지 참여했어요.".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TermsAgreementRepository termsAgreementRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private RawRecordUploadRepository rawRecordUploadRepository;
    @Autowired private RawRecordUploadService rawRecordUploadService;
    @Autowired private RawFileStorage rawFileStorage;

    private User staff;
    private User otherStaff;

    @BeforeEach
    void setUp() {
        Organization organization = organizationRepository.save(OrganizationFixture.center());
        Organization other = organizationRepository.save(OrganizationFixture.center());
        staff = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), organization.getId())));
        otherStaff = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.organizationUser(uniqueKakaoId(), other.getId())));
    }

    @Test
    void 주소를_받아_올리고_완료하면_원본_기록과_일지가_생긴다() throws Exception {
        Long uploadId = issue(staff, "0821_관찰일지.txt", "text/plain", NOTE.length);

        putContent(staff, uploadId, "text/plain", NOTE);
        String body = mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("0821_관찰일지.txt"))
                .andExpect(jsonPath("$.data.contentType").value("text/plain"))
                .andExpect(jsonPath("$.data.sizeBytes").value(NOTE.length))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Long rawRecordId = ((Number) JsonPath.read(body, "$.data.id")).longValue();

        List<JournalEntry> entries =
                journalEntryRepository.findByRawRecordIdInAndDeletedAtIsNullOrderByIdAsc(List.of(rawRecordId));
        assertThat(entries).extracting(JournalEntry::getContent).containsExactly("오후 미술 활동에 끝까지 참여했어요.");
        mockMvc.perform(get("/api/v1/raw-records/" + rawRecordId).cookie(auth(staff)))
                .andExpect(status().isOk());
    }

    @Test
    void 완료를_두_번_불러도_원본_기록은_하나다() throws Exception {
        Long uploadId = issue(staff, "note.txt", "text/plain", NOTE.length);
        putContent(staff, uploadId, "text/plain", NOTE);

        Long first = complete(staff, uploadId);
        Long second = complete(staff, uploadId);

        assertThat(second).isEqualTo(first);
        assertThat(journalEntryRepository.findByRawRecordIdInAndDeletedAtIsNullOrderByIdAsc(List.of(first)))
                .hasSize(1);
    }

    @Test
    void 업로드_주소에는_서명할_형식이_실리고_로컬은_BE_주소다() throws Exception {
        mockMvc.perform(withCsrf(post(UPLOAD_URL), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadUrlBody("note.csv", "text/csv", 5)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.uploadId").isNumber())
                .andExpect(jsonPath("$.data.uploadUrl").value(startsWith("/api/v1/raw-records/uploads/")))
                .andExpect(jsonPath("$.data.method").value("PUT"))
                .andExpect(jsonPath("$.data.headers['Content-Type']").value("text/csv"))
                .andExpect(jsonPath("$.data.expiresAt").isString());
    }

    @Test
    void 허용되지_않은_확장자는_주소를_주지_않는다() throws Exception {
        mockMvc.perform(withCsrf(post(UPLOAD_URL), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadUrlBody("photo.png", "image/png", 10)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_INVALID_REQUEST"));
    }

    @Test
    void 크기가_없거나_0이면_400() throws Exception {
        mockMvc.perform(withCsrf(post(UPLOAD_URL), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadUrlBody("note.txt", "text/plain", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_INVALID_REQUEST"));
    }

    @Test
    void 상한보다_큰_파일은_413() throws Exception {
        mockMvc.perform(withCsrf(post(UPLOAD_URL), auth(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadUrlBody("big.pdf", "application/pdf", 20L * 1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_FILE_TOO_LARGE"));
    }

    @Test
    void 기관_소속이_아니면_주소를_주지_않는다() throws Exception {
        User parent = TermsFixture.agreeAll(termsAgreementRepository,
                userRepository.save(UserFixture.parent(uniqueKakaoId())));

        mockMvc.perform(withCsrf(post(UPLOAD_URL), auth(parent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadUrlBody("note.txt", "text/plain", 5)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 파일을_올리기_전에_완료하면_409이고_올린_뒤에는_된다() throws Exception {
        Long uploadId = issue(staff, "note.txt", "text/plain", NOTE.length);

        mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(staff)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_UPLOAD_NOT_READY"));

        putContent(staff, uploadId, "text/plain", NOTE);
        complete(staff, uploadId);
    }

    @Test
    void 다른_기관의_접수는_404() throws Exception {
        Long uploadId = issue(staff, "note.txt", "text/plain", NOTE.length);

        mockMvc.perform(withCsrf(put(contentPath(uploadId)), auth(otherStaff))
                        .contentType("text/plain").content(NOTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_UPLOAD_NOT_FOUND"));
        mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(otherStaff)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_UPLOAD_NOT_FOUND"));
    }

    @Test
    void 일지_상한을_넘는_파일은_400이고_접수를_지운다() throws Exception {
        byte[] tooLong = "가".repeat(JournalEntry.MAX_CONTENT_LENGTH + 1).getBytes(StandardCharsets.UTF_8);
        Long uploadId = issue(staff, "long.txt", "text/plain", tooLong.length);
        putContent(staff, uploadId, "text/plain", tooLong);
        String storedPath = rawRecordUploadRepository.findById(uploadId).orElseThrow().getStoredPath();

        mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(staff)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_ENTRY_LIMIT_EXCEEDED"));

        assertThat(rawRecordUploadRepository.findById(uploadId)).isEmpty();
        assertThat(rawFileStorage.sizeOf(storedPath)).isEmpty();
        mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(staff)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 접수와_다른_형식이나_크기로는_올릴_수_없다() throws Exception {
        Long uploadId = issue(staff, "note.txt", "text/plain", NOTE.length);

        mockMvc.perform(withCsrf(put(contentPath(uploadId)), auth(staff))
                        .contentType("text/csv").content(NOTE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_INVALID_REQUEST"));
        mockMvc.perform(withCsrf(put(contentPath(uploadId)), auth(staff))
                        .contentType("text/plain").content("짧다".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_RECORD_INVALID_REQUEST"));
        mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(staff)))
                .andExpect(status().isConflict());
    }

    @Test
    void 정리는_하루_지난_접수를_지우고_완료되지_않은_파일만_돌려준다() throws Exception {
        Long abandoned = issue(staff, "left.txt", "text/plain", NOTE.length);
        putContent(staff, abandoned, "text/plain", NOTE);
        Long done = issue(staff, "done.txt", "text/plain", NOTE.length);
        putContent(staff, done, "text/plain", NOTE);
        complete(staff, done);
        String abandonedPath = rawRecordUploadRepository.findById(abandoned).orElseThrow().getStoredPath();
        String donePath = rawRecordUploadRepository.findById(done).orElseThrow().getStoredPath();

        List<String> abandonedKeys = new ArrayList<>();
        RawRecordUploadService.Swept swept;
        do {
            swept = rawRecordUploadService.sweepBatch(LocalDateTime.now().plusDays(2));
            abandonedKeys.addAll(swept.abandonedKeys());
        } while (swept.uploads() == RawRecordUploadService.SWEEP_BATCH_SIZE);

        assertThat(rawRecordUploadRepository.findById(abandoned)).isEmpty();
        assertThat(rawRecordUploadRepository.findById(done)).isEmpty();
        assertThat(abandonedKeys).contains(abandonedPath).doesNotContain(donePath);
    }

    @Test
    void 정리는_하루가_안_된_접수를_건드리지_않는다() throws Exception {
        Long fresh = issue(staff, "fresh.txt", "text/plain", NOTE.length);

        rawRecordUploadService.sweepBatch(LocalDateTime.now());

        assertThat(rawRecordUploadRepository.findById(fresh)).isPresent();
    }

    private Long issue(User user, String filename, String contentType, long size) throws Exception {
        String body = mockMvc.perform(withCsrf(post(UPLOAD_URL), auth(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadUrlBody(filename, contentType, size)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.uploadId")).longValue();
    }

    private void putContent(User user, Long uploadId, String contentType, byte[] content) throws Exception {
        mockMvc.perform(withCsrf(put(contentPath(uploadId)), auth(user)).contentType(contentType).content(content))
                .andExpect(status().isOk());
    }

    private Long complete(User user, Long uploadId) throws Exception {
        String body = mockMvc.perform(withCsrf(post(completePath(uploadId)), auth(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    private static String uploadUrlBody(String filename, String contentType, long size) {
        return """
                {"filename": "%s", "contentType": "%s", "size": %d}
                """.formatted(filename, contentType, size);
    }

    private static String contentPath(Long uploadId) {
        return "/api/v1/raw-records/uploads/" + uploadId + "/content";
    }

    private static String completePath(Long uploadId) {
        return "/api/v1/raw-records/uploads/" + uploadId + "/complete";
    }

    private static String uniqueKakaoId() {
        return "upload-" + UUID.randomUUID();
    }

    private Cookie auth(User user) {
        return new Cookie(JwtCookie.NAME, jwtProvider.createToken(String.valueOf(user.getId())));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie... cookies)
            throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/health")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        Cookie[] all = new Cookie[cookies.length + 1];
        System.arraycopy(cookies, 0, all, 0, cookies.length);
        all[cookies.length] = csrf;
        return request.cookie(all).header("X-XSRF-TOKEN", csrf.getValue());
    }
}
