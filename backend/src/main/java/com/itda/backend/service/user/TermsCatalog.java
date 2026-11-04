package com.itda.backend.service.user;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;

/**
 * 약관마다 지금 판과, 판이 바뀌면 다시 동의를 받는지.
 *
 * <p>판을 올릴 때 여기만 고친다. 약관 본문은 아직 없어 판은 날짜 이름표일 뿐이다.
 * <ul>
 *   <li>이용약관 — 한 번 동의했으면 판이 바뀌어도 통과한다. 바뀐 내용은 공지로 알린다(공지 기능은 아직 없다).</li>
 *   <li>개인정보와 민감정보 수집 — 수집 항목이 바뀌면 다시 받아야 하므로 지금 판에 동의해야 통과한다.</li>
 * </ul>
 *
 * <p>switch 로 적어 둔 이유: {@link TermKey} 에 값을 늘리면 여기가 컴파일되지 않아 판을 빠뜨릴 수 없다.
 */
public final class TermsCatalog {

    private TermsCatalog() {
    }

    public static String currentVersion(TermKey key) {
        return switch (key) {
            case TOS -> "2026-11-03";
            case PRIVACY -> "2026-11-03";
        };
    }

    static boolean reconsentOnChange(TermKey key) {
        return switch (key) {
            case TOS -> false;
            case PRIVACY -> true;
        };
    }

    /** 지금은 모든 약관이 필수다. */
    public static Set<TermKey> required() {
        return EnumSet.allOf(TermKey.class);
    }

    /** 필수 약관마다, 다시 받는 약관이면 지금 판 줄이, 아니면 어느 판이든 줄이 있어야 동의한 것이다. */
    public static boolean isAgreed(Collection<TermsAgreement> agreements) {
        return required().stream().allMatch(key -> agreements.stream()
                .filter(agreement -> agreement.getTermKey() == key)
                .anyMatch(agreement -> !reconsentOnChange(key)
                        || currentVersion(key).equals(agreement.getVersion())));
    }
}
