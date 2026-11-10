package com.itda.backend.service.user;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.repository.user.TermsAgreementRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TermsService {

    private final TermsAgreementRepository termsAgreementRepository;

    @Transactional
    public void agree(User user, Collection<TermKey> agreed, Map<TermKey, String> versions) {
        if (!user.isSignupCompleted()) {
            throw new UserException(UserErrorCode.SIGNUP_NOT_COMPLETED);
        }
        Set<TermKey> required = TermsCatalog.required(user.getRole());
        if (!required.containsAll(agreed) || !agreed.containsAll(required)) {
            throw new UserException(UserErrorCode.INVALID_TERMS_AGREEMENT);
        }
        if (versions != null) {
            if (!versions.keySet().equals(required)) {
                throw new UserException(UserErrorCode.INVALID_TERMS_AGREEMENT);
            }
            boolean changed = required.stream()
                    .anyMatch(key -> !TermsCatalog.currentVersion(key).equals(versions.get(key)));
            if (changed) {
                throw new UserException(UserErrorCode.TERMS_VERSION_CHANGED);
            }
        }
        Set<String> existing = termsAgreementRepository.findByUserId(user.getId()).stream()
                .map(agreement -> rowKey(agreement.getTermKey(), agreement.getVersion()))
                .collect(Collectors.toSet());
        List<TermsAgreement> missing = required.stream()
                .map(key -> TermsAgreement.agree(user.getId(), key,
                        versions != null ? TermsCatalog.currentVersion(key) : TermsCatalog.labelOnlyVersion(key)))
                .filter(row -> !existing.contains(rowKey(row.getTermKey(), row.getVersion())))
                .toList();
        termsAgreementRepository.saveAll(missing);
    }

    private static String rowKey(TermKey key, String version) {
        return key.name() + "@" + version;
    }

    @Transactional(readOnly = true)
    public boolean isAgreed(User user) {
        if (!user.isSignupCompleted()) {
            return false;
        }
        return TermsCatalog.isAgreed(user.getRole(), termsAgreementRepository.findByUserId(user.getId()));
    }
}
