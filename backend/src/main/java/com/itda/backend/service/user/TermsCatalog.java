package com.itda.backend.service.user;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.UserRole;

public final class TermsCatalog {

    private TermsCatalog() {
    }

    public static String currentVersion(TermKey key) {
        return switch (key) {
            case TOS, ORG_TOS, ORG_CHILD_DATA -> "2026-11-07";
            case PRIVACY, ORG_PRIVACY -> "2026-11-09";
        };
    }

    private static Set<String> stillAcceptedVersions(TermKey key) {
        return switch (key) {
            case TOS, ORG_TOS, ORG_CHILD_DATA -> Set.of();
            case PRIVACY, ORG_PRIVACY -> Set.of("2026-11-07");
        };
    }

    public static String labelOnlyVersion(TermKey key) {
        return switch (key) {
            case TOS, PRIVACY -> "2026-11-03";
            case ORG_TOS, ORG_PRIVACY, ORG_CHILD_DATA -> "2026-11-05";
        };
    }

    public static Set<String> acceptedVersions(TermKey key) {
        Set<String> accepted = new HashSet<>(stillAcceptedVersions(key));
        accepted.add(labelOnlyVersion(key));
        accepted.add(currentVersion(key));
        return Set.copyOf(accepted);
    }

    public static boolean isDraft(TermKey key) {
        return switch (key) {
            case TOS, PRIVACY, ORG_TOS, ORG_PRIVACY, ORG_CHILD_DATA -> true;
        };
    }

    static boolean reconsentOnChange(TermKey key) {
        return switch (key) {
            case TOS, ORG_TOS -> false;
            case PRIVACY, ORG_PRIVACY, ORG_CHILD_DATA -> true;
        };
    }

    public static Set<TermKey> required(UserRole role) {
        return switch (role) {
            case PARENT -> EnumSet.of(TermKey.TOS, TermKey.PRIVACY);
            case ORGANIZATION -> EnumSet.of(TermKey.ORG_TOS, TermKey.ORG_PRIVACY, TermKey.ORG_CHILD_DATA);
        };
    }

    public static boolean isAgreed(UserRole role, Collection<TermsAgreement> agreements) {
        return required(role).stream().allMatch(key -> agreements.stream()
                .filter(agreement -> agreement.getTermKey() == key)
                .anyMatch(agreement -> !reconsentOnChange(key)
                        || acceptedVersions(key).contains(agreement.getVersion())));
    }
}
