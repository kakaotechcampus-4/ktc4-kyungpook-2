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

    private static String path(TermKey key, String version) {
        return "terms/" + key.toJson() + "/" + version + ".txt";
    }
}
