package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

import com.itda.backend.domain.user.TermKey;

class TermsCatalogTest {

    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 지금_판은_통과_판에_든다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key)).contains(TermsCatalog.currentVersion(key));
    }

    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 호환_기간에는_라벨_판도_통과_판에_든다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key)).contains(TermsCatalog.labelOnlyVersion(key));
    }

    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 판_이름은_20자_안이다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key)).allSatisfy(version -> assertThat(version).hasSizeLessThanOrEqualTo(20));
    }

    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 라벨_판에는_문안이_없고_지금_판에는_있다(TermKey key) {
        assertThat(new ClassPathResource(path(key, TermsCatalog.labelOnlyVersion(key))).exists()).isFalse();
        assertThat(new ClassPathResource(path(key, TermsCatalog.currentVersion(key))).exists()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(TermKey.class)
    void 라벨_판이_아닌_통과_판에는_모두_문안이_있다(TermKey key) {
        assertThat(TermsCatalog.acceptedVersions(key))
                .filteredOn(version -> !version.equals(TermsCatalog.labelOnlyVersion(key)))
                .allSatisfy(version -> assertThat(new ClassPathResource(path(key, version)).exists()).isTrue());
    }

    @ParameterizedTest
    @EnumSource(value = TermKey.class, names = {"PRIVACY", "ORG_PRIVACY"})
    void 빈칸만_채운_개인정보_판은_옛_판_동의도_통과한다(TermKey key) {
        assertThat(TermsCatalog.currentVersion(key)).isEqualTo("2026-11-11");
        assertThat(TermsCatalog.acceptedVersions(key)).contains("2026-11-07", "2026-11-09");
    }

    private static String path(TermKey key, String version) {
        return "terms/" + key.toJson() + "/" + version + ".txt";
    }
}
