package com.itda.backend.dto.response.user;

import io.swagger.v3.oas.annotations.media.Schema;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.service.user.TermsDocument;

public record TermsDocumentResponse(
        @Schema(description = "약관 키. 동의 요청의 agreed · versions 키와 같다", example = "privacy")
        TermKey key,
        @Schema(example = "개인정보와 민감정보 수집 · 이용")
        String title,
        @Schema(description = "지금 판. 동의할 때 versions 에 그대로 보낸다", example = "2026-11-07")
        String version,
        @Schema(description = "시행일 (YYYY-MM-DD)", example = "2026-11-07")
        String effectiveDate,
        @Schema(description = "팀 검토 전 초안이면 true. 화면은 본문 위에 초안 표시를 띄운다")
        boolean draft,
        @Schema(description = "본문. 줄바꿈만 살리는 그냥 글이다 — 조항 번호와 \"- \" 목록은 글자 그대로")
        String body
) {

    public static TermsDocumentResponse from(TermsDocument document) {
        return new TermsDocumentResponse(document.key(), document.title(), document.version(),
                document.version().substring(0, Math.min(10, document.version().length())),
                document.draft(), document.body());
    }
}
