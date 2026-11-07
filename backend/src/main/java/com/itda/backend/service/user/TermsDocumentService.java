package com.itda.backend.service.user;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.UserRole;

/**
 * 약관 본문. 지금 판({@link TermsCatalog#currentVersion})의 문안 파일 {@code resources/terms/{key}/{판}.txt} 를 준다.
 *
 * <p>파일의 첫 줄이 제목, 그 아래가 본문이다. 본문은 줄바꿈만 살리는 그냥 글이다.
 *
 * <p><b>기동할 때 다섯 개를 모두 읽어 둔다.</b> 판을 올리며 파일을 빠뜨리면 화면에서 처음 열 때가 아니라 기동 때 실패해
 * 어느 파일인지 바로 보인다. 문안은 배포 때만 바뀌므로 다시 읽지 않는다.
 */
@Service
public class TermsDocumentService {

    private final Map<TermKey, TermsDocument> documents = new EnumMap<>(TermKey.class);

    public TermsDocumentService() {
        for (TermKey key : TermKey.values()) {
            documents.put(key, load(key));
        }
    }

    /** 그 역할의 필수 약관을 {@link TermsCatalog#required} 순서(약관 키 선언 순서)로. */
    public List<TermsDocument> forRole(UserRole role) {
        return TermsCatalog.required(role).stream().map(documents::get).toList();
    }

    private static TermsDocument load(TermKey key) {
        String version = TermsCatalog.currentVersion(key);
        String path = path(key, version);
        ClassPathResource resource = new ClassPathResource(path);
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("약관 문안 파일이 없다: " + path, e);
        }
        int newline = text.indexOf('\n');
        if (newline < 0) {
            throw new IllegalStateException("약관 문안의 첫 줄(제목) 아래에 본문이 없다: " + path);
        }
        String title = text.substring(0, newline).strip();
        String body = text.substring(newline + 1).strip();
        if (title.isEmpty() || body.isEmpty()) {
            throw new IllegalStateException("약관 문안의 제목이나 본문이 비었다: " + path);
        }
        return new TermsDocument(key, title, version, TermsCatalog.isDraft(key), body);
    }

    /** 문안 파일의 클래스패스 경로. 키는 API 와 같은 소문자다. */
    static String path(TermKey key, String version) {
        return "terms/" + key.toJson() + "/" + version + ".txt";
    }
}
