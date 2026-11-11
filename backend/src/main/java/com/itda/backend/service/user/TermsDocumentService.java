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

@Service
public class TermsDocumentService {

    private final Map<TermKey, TermsDocument> documents = new EnumMap<>(TermKey.class);

    public TermsDocumentService() {
        for (TermKey key : TermKey.values()) {
            documents.put(key, load(key));
        }
    }

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

    static String path(TermKey key, String version) {
        return "terms/" + key.toJson() + "/" + version + ".txt";
    }
}
