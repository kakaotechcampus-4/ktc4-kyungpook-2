package com.itda.backend.fixture;

import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.service.user.TermsCatalog;

/**
 * 회원(보호자 · 기관 담당자)이 자기 역할의 지금 판 약관에 모두 동의한 상태를 만든다.
 *
 * <p>동의하지 않은 회원은 보안 필터에서 403 TERMS_NOT_AGREED 로 막힌다. 그래서 MockMvc · HTTP 로 API 를
 * 부르는 테스트는 회원을 저장한 직후 이것을 부른다. 서비스 · JPA 테스트는 필터를 거치지 않아 필요 없다.
 * {@link UserFixture} 는 저장 전 엔티티라 거기서 함께 만들 수 없다.
 */
public final class TermsFixture {

    private TermsFixture() {
    }

    /** @return 받은 회원 그대로 — {@code agreeAll(repo, userRepository.save(...))} 처럼 이어 쓴다 */
    public static User agreeAll(TermsAgreementRepository repository, User user) {
        for (var key : TermsCatalog.required(user.getRole())) {
            repository.save(TermsAgreement.agree(user.getId(), key, TermsCatalog.currentVersion(key)));
        }
        return user;
    }
}
