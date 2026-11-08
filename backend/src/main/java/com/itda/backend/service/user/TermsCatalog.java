package com.itda.backend.service.user;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.UserRole;

/**
 * 약관마다 지금 판과, 판이 바뀌면 다시 동의를 받는지. 역할마다 어느 약관이 필수인지.
 *
 * <p><b>한 판 = 한 본문.</b> 판마다 문안 파일({@code resources/terms/{key}/{판}.txt})이 하나 있고, 내보낸 파일은
 * 고치지 않는다(초안을 고칠 때도 새 판을 낸다). 판을 올릴 때는 새 파일 · {@code CHECKSUMS} 한 줄 · 여기를 함께 고친다.
 *
 * <p>본문 판과 "다시 동의를 요구하는가"는 따로 정한다 — {@link #currentVersion} 은 지금 보여 주는 판,
 * {@link #acceptedVersions} 는 다시 받는 약관에서 통과로 칠 판이다. 초안을 고쳐 판을 올려도 통과 판에 더하면
 * 이미 동의한 사람에게 다시 받지 않는다.
 * <ul>
 *   <li>이용약관(보호자 · 기관) — 한 번 동의했으면 판이 바뀌어도 통과한다. 바뀐 내용은 공지로 알린다(공지 기능은 아직 없다).</li>
 *   <li>개인정보 수집(보호자의 개인정보와 민감정보 · 기관 담당자 개인정보) — 수집 항목이 바뀌면 다시 받아야 하므로
 *       통과 판에 동의해야 통과한다.</li>
 *   <li>아동 정보 보호 서약(기관) — 기관이 지킬 의무라, 통과 판에 동의해야 통과한다.</li>
 * </ul>
 *
 * <p>switch 로 적어 둔 이유: {@link TermKey} · {@link UserRole} 에 값을 늘리면 여기가 컴파일되지 않아 빠뜨릴 수 없다.
 */
public final class TermsCatalog {

    private TermsCatalog() {
    }

    /** 지금 보여 주는 본문의 판. 동의 화면이 이 판을 받아 두었다가 동의할 때 보낸다. */
    public static String currentVersion(TermKey key) {
        return switch (key) {
            case TOS, ORG_TOS, ORG_CHILD_DATA -> "2026-11-07";
            // 11-09: 수집 항목에서 카카오 닉네임을 뺐다 — 서버가 회원번호만 받는다
            case PRIVACY, ORG_PRIVACY -> "2026-11-09";
        };
    }

    /**
     * 지금 판 전에 낸 본문 판 중 지금도 통과로 치는 판. 수집 항목이 줄기만 한 개정이면 옛 판 동의로 지금 판도 덮인다고 보고
     * 다시 받지 않는다. 수집 항목이 늘거나 이용 목적이 바뀌면 여기에 넣지 않는다.
     */
    private static Set<String> stillAcceptedVersions(TermKey key) {
        return switch (key) {
            case TOS, ORG_TOS, ORG_CHILD_DATA -> Set.of();
            // 11-07 판과 다른 점은 카카오 닉네임을 더 모으지 않는다는 것뿐이다
            case PRIVACY, ORG_PRIVACY -> Set.of("2026-11-07");
        };
    }

    /**
     * 본문 없이 라벨만 있던 판. 판을 보내지 않은 동의(본문을 보여 주지 못한 옛 화면)를 이 판으로 기록한다 —
     * 본문을 읽지 않은 동의가 본문 판에 남지 않게. 이 판에는 문안 파일이 없다.
     */
    public static String labelOnlyVersion(TermKey key) {
        return switch (key) {
            case TOS, PRIVACY -> "2026-11-03";
            case ORG_TOS, ORG_PRIVACY, ORG_CHILD_DATA -> "2026-11-05";
        };
    }

    /**
     * 다시 받는 약관에서 통과로 칠 판. 지금 판은 늘 든다. 라벨 판은 <b>판 없는 동의를 받는 호환 기간 동안</b> 든다 —
     * 빼면 판 없이 동의한 사람이 통과하지 못해 같은 요청을 되풀이한다. 수집이 줄기만 한 옛 판({@link #stillAcceptedVersions})도 든다.
     * 초안을 확정할 때는 판을 필수로 바꾼 뒤 확정 판 하나로 줄여 다시 받는다.
     */
    public static Set<String> acceptedVersions(TermKey key) {
        Set<String> accepted = new HashSet<>(stillAcceptedVersions(key));
        accepted.add(labelOnlyVersion(key));
        accepted.add(currentVersion(key));
        return Set.copyOf(accepted);
    }

    /** 팀 검토 전 초안인지. 화면이 본문 위에 초안 띠를 띄운다. 확정하면 판을 올리며 끈다. */
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

    /** 그 역할이 동의해야 하는 약관. 지금은 역할마다 받는 약관이 모두 필수다. 다른 역할의 약관은 받지 않는다. */
    public static Set<TermKey> required(UserRole role) {
        return switch (role) {
            case PARENT -> EnumSet.of(TermKey.TOS, TermKey.PRIVACY);
            case ORGANIZATION -> EnumSet.of(TermKey.ORG_TOS, TermKey.ORG_PRIVACY, TermKey.ORG_CHILD_DATA);
        };
    }

    /**
     * 그 역할의 필수 약관마다, 다시 받는 약관이면 통과 판({@link #acceptedVersions}) 줄이, 아니면 어느 판이든 줄이 있어야 동의한 것이다.
     * 다른 역할 약관의 줄은 보지 않는다.
     */
    public static boolean isAgreed(UserRole role, Collection<TermsAgreement> agreements) {
        return required(role).stream().allMatch(key -> agreements.stream()
                .filter(agreement -> agreement.getTermKey() == key)
                .anyMatch(agreement -> !reconsentOnChange(key)
                        || acceptedVersions(key).contains(agreement.getVersion())));
    }
}
