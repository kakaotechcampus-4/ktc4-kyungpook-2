package com.itda.backend.service.user;

import com.itda.backend.domain.user.TermKey;

/**
 * 약관 한 판의 본문.
 *
 * @param version 판 이름. 동의 화면이 이 값을 받아 두었다가 동의할 때 보낸다
 * @param draft   팀 검토 전 초안이면 true — 화면이 본문 위에 초안 띠를 띄운다
 */
public record TermsDocument(TermKey key, String title, String version, boolean draft, String body) {
}
