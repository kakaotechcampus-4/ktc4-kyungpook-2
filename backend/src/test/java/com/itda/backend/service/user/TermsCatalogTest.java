package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

import com.itda.backend.domain.user.TermKey;

/**
 * 판 규칙이 서로 맞는지. 판을 올리며 한쪽만 고치면 여기서 깨진다.
 */
class TermsCatalogTest {

    /** 지금 판에 동의한 사람이 다시 미동의로 갇히지 않게. */
    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 지금_판은_통과_판에_든다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key)).contains(TermsCatalog.currentVersion(key));
    }

    /** 판 없는 동의를 받는 호환 기간에는 라벨 판으로 기록한 사람도 통과해야 한다. 확정 때 이 테스트를 함께 바꾼다. */
    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 호환_기간에는_라벨_판도_통과_판에_든다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key)).contains(TermsCatalog.labelOnlyVersion(key));
    }

    /** terms_agreement.version 은 VARCHAR(20) 이다. */
    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 판_이름은_20자_안이다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key)).allSatisfy(version -> assertThat(version).hasSizeLessThanOrEqualTo(20));
    }

    /** 라벨 판은 본문이 없던 판이다 — 문안을 붙이면 라벨에만 동의한 사람이 그 글에 동의한 것처럼 된다. */
    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 라벨_판에는_문안이_없고_지금_판에는_있다(TermKey key) {
        assertThat(new ClassPathResource(path(key, TermsCatalog.labelOnlyVersion(key))).exists()).isFalse();
        assertThat(new ClassPathResource(path(key, TermsCatalog.currentVersion(key))).exists()).isTrue();
    }

    /** 통과 판 중 라벨 판이 아닌 판은 본문이 있어야 한다 — 동의한 글이 무엇인지 남아 있게. */
    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 라벨_판이_아닌_통과_판에는_모두_문안이_있다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key))
                .filteredOn(version -> !version.equals(TermsCatalog.labelOnlyVersion(key)))
                .allSatisfy(version -> assertThat(new ClassPathResource(path(key, version)).exists()).isTrue());
    }

    /** 11-09 판은 카카오 닉네임을 수집 항목에서 뺐을 뿐이라, 11-07 판에 동의한 사람에게 다시 받지 않는다. */
    @ParameterizedTest
    @EnumSource(value = TermKey.class, names = {"PRIVACY", "ORG_PRIVACY"})
    void 닉네임만_뺀_개인정보_판은_옛_판_동의도_통과한다(TermKey key) {
        assertThat(TermsCatalog.currentVersion(key)).isEqualTo("2026-11-09");
        assertThat(TermsCatalog.acceptedVersions(key)).contains("2026-11-07");
    }

    private static String path(TermKey key, String version) {
        return "terms/" + key.toJson() + "/" + version + ".txt";
    }
}
