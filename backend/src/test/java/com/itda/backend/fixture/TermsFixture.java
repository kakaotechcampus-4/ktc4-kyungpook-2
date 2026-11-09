package com.itda.backend.fixture;

import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.repository.user.TermsAgreementRepository;
import com.itda.backend.service.user.TermsCatalog;

public final class TermsFixture {

    private TermsFixture() {
    }

    public static User agreeAll(TermsAgreementRepository repository, User user) {
        for (var key : TermsCatalog.required(user.getRole())) {
            repository.save(TermsAgreement.agree(user.getId(), key, TermsCatalog.currentVersion(key)));
        }
        return user;
    }
}
