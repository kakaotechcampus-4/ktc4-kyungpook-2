package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.UserRole;

/**
 * 약관 본문 파일과 체크섬 목록.
 *
 * <p>한 판 = 한 본문. 내보낸 문안 파일을 고치면 {@code terms/CHECKSUMS} 와 어긋나 여기서 깨진다 — 글을 바꾸려면
 * 새 판 파일을 더하고 목록에 한 줄을 더한다(그 줄이 리뷰에 보인다).
 */
class TermsDocumentServiceTest {

    private final TermsDocumentService service = new TermsDocumentService();

    @Test
    void 보호자는_이용약관과_개인정보_수집_순서로_받는다() {
        assertThat(service.forRole(UserRole.PARENT))
                .extracting(TermsDocument::key)
                .containsExactly(TermKey.TOS, TermKey.PRIVACY);
    }

    @Test
    void 기관은_이용약관_개인정보_수집_서약_순서로_받는다() {
        assertThat(service.forRole(UserRole.ORGANIZATION))
                .extracting(TermsDocument::key)
                .containsExactly(TermKey.ORG_TOS, TermKey.ORG_PRIVACY, TermKey.ORG_CHILD_DATA);
    }

    @Test
    void 약관마다_지금_판의_제목과_본문이_있다() {
        for (UserRole role : UserRole.values()) {
            assertThat(service.forRole(role)).allSatisfy(document -> {
                assertThat(document.version()).isEqualTo(TermsCatalog.currentVersion(document.key()));
                assertThat(document.title()).isNotBlank();
                assertThat(document.body()).isNotBlank().doesNotStartWith(document.title());
                assertThat(document.draft()).isEqualTo(TermsCatalog.isDraft(document.key()));
            });
        }
    }

    @Test
    void 모든_문안_파일이_체크섬_목록과_맞고_목록에_없는_파일이_없다() throws IOException {
        Map<String, String> listed = checksums();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:terms/*/*.txt");
        Map<String, String> actual = Arrays.stream(files).collect(Collectors.toMap(
                TermsDocumentServiceTest::relativePath, TermsDocumentServiceTest::sha256));

        assertThat(actual)
                .as("내보낸 판의 문안은 고치지 않는다. 글을 바꾸려면 새 판 파일을 더하고 CHECKSUMS 에 한 줄을 더한다")
                .isEqualTo(listed);
    }

    private static Map<String, String> checksums() throws IOException {
        try (InputStream in = new ClassPathResource("terms/CHECKSUMS").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split("\\s+", 2))
                    .collect(Collectors.toMap(parts -> parts[1], parts -> parts[0]));
        }
    }

    /** {@code .../terms/privacy/2026-11-07.txt} → {@code privacy/2026-11-07.txt} */
    private static String relativePath(Resource resource) {
        try {
            String url = resource.getURL().toString();
            return url.substring(url.lastIndexOf("/terms/") + "/terms/".length());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
