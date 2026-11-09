package com.itda.backend.service.user;

import com.itda.backend.domain.user.TermKey;

public record TermsDocument(TermKey key, String title, String version, boolean draft, String body) {
}
