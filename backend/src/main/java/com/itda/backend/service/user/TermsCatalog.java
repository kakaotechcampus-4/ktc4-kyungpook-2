package com.itda.backend.service.user;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.UserRole;

/**
 * 약관마다 지금 판과, 판이 바뀌면 다시 동의를 받는지. 역할마다 어느 약관이 필수인지.
 *
 * <p>판을 올릴 때 여기만 고친다. 약관 본문은 아직 없어 판은 날짜 이름표일 뿐이다.
 * <ul>
 *   <li>이용약관(보호자 · 기관) — 한 번 동의했으면 판이 바뀌어도 통과한다. 바뀐 내용은 공지로 알린다(공지 기능은 아직 없다).</li>
 *   <li>개인정보 수집(보호자의 개인정보와 민감정보 · 기관 담당자 개인정보) — 수집 항목이 바뀌면 다시 받아야 하므로
 *       지금 판에 동의해야 통과한다.</li>
 *   <li>아동 정보 보호 서약(기관) — 기관이 지킬 의무라, 바뀌면 바뀐 의무에 다시 동의해야 통과한다.</li>
 * </ul>
 *
 * <p>switch 로 적어 둔 이유: {@link TermKey} · {@link UserRole} 에 값을 늘리면 여기가 컴파일되지 않아 빠뜨릴 수 없다.
 */
public final class TermsCatalog {

    private TermsCatalog() {
    }

    public static String currentVersion(TermKey key) {
        return switch (key) {
            case TOS -> "2026-11-03";
            case PRIVACY -> "2026-11-03";
            case ORG_TOS -> "2026-11-05";
            case ORG_PRIVACY -> "2026-11-05";
            case ORG_CHILD_DATA -> "2026-11-05";
        };
    }

    static boolean reconsentOnChange(TermKey key) {
        return switch (key) {
            case TOS, ORG_TOS -> false;
            case PRIVACY, ORG_PRIVACY, ORG_CHILD_DATA -> true;
        };
    }

    /** 그 역할이 동의해야 하는 약관. 지금은 역할마다 받는 약관이 모두 필수다. 다른 역할의 약관은 받지 않는다. */
    public static Set<TermKey> required(UserRole role) {
        return switch (role) {
            case PARENT -> EnumSet.of(TermKey.TOS, TermKey.PRIVACY);
            case ORGANIZATION -> EnumSet.of(TermKey.ORG_TOS, TermKey.ORG_PRIVACY, TermKey.ORG_CHILD_DATA);
        };
    }

    /**
     * 그 역할의 필수 약관마다, 다시 받는 약관이면 지금 판 줄이, 아니면 어느 판이든 줄이 있어야 동의한 것이다.
     * 다른 역할 약관의 줄은 보지 않는다.
     */
    public static boolean isAgreed(UserRole role, Collection<TermsAgreement> agreements) {
        return required(role).stream().allMatch(key -> agreements.stream()
                .filter(agreement -> agreement.getTermKey() == key)
                .anyMatch(agreement -> !reconsentOnChange(key)
                        || currentVersion(key).equals(agreement.getVersion())));
    }
}
