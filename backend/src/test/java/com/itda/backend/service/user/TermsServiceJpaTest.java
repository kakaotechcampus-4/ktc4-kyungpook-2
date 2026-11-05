package com.itda.backend.service.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.itda.backend.domain.user.TermKey;
import com.itda.backend.domain.user.TermsAgreement;
import com.itda.backend.domain.user.User;
import com.itda.backend.exception.user.UserErrorCode;
import com.itda.backend.exception.user.UserException;
import com.itda.backend.fixture.UserFixture;
import com.itda.backend.repository.user.TermsAgreementRepository;

/**
 * 약관 동의가 실제로 몇 줄을 남기는지, 남은 줄로 동의 여부를 어떻게 판정하는지 본다.
 *
 * <p>"판이 바뀐 뒤" 는 옛 판 줄을 직접 넣어 재현한다 — 상수를 바꾸지 않아도 같은 상황이다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(TermsService.class)
class TermsServiceJpaTest {

    private static final List<TermKey> ALL = List.of(TermKey.TOS, TermKey.PRIVACY);
    private static final List<TermKey> ORG_ALL = List.of(TermKey.ORG_TOS, TermKey.ORG_PRIVACY, TermKey.ORG_CHILD_DATA);
    private static final String OLD = "2000-01-01";

    @Autowired
    private TermsService termsService;

    @Autowired
    private TermsAgreementRepository termsAgreementRepository;

    @Autowired
    private TestEntityManager em;

    private User parent;

    @BeforeEach
    void setUp() {
        parent = em.persist(UserFixture.parent("terms-parent", "김보호"));
        em.flush();
    }

    @Test
    void 동의하면_약관마다_지금_판_줄이_하나씩_생긴다() {
        termsService.agree(parent, ALL);
        em.flush();

        assertThat(termsAgreementRepository.findByUserId(parent.getId()))
                .extracting(TermsAgreement::getTermKey, TermsAgreement::getVersion)
                .containsExactlyInAnyOrder(
                        tuple(TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)),
                        tuple(TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));
        assertThat(termsAgreementRepository.findByUserId(parent.getId()))
                .allSatisfy(agreement -> assertThat(agreement.getCreatedAt()).isNotNull());
        assertThat(termsService.isAgreed(parent)).isTrue();
    }

    @Test
    void 같은_판에_다시_동의해도_줄이_늘지_않는다() {
        termsService.agree(parent, ALL);
        em.flush();
        termsService.agree(parent, List.of(TermKey.PRIVACY, TermKey.TOS, TermKey.TOS));
        em.flush();

        assertThat(termsAgreementRepository.findByUserId(parent.getId())).hasSize(2);
    }

    /** 옛 판 줄은 지우거나 고치지 않는다. 다시 동의하면 지금 판 줄이 옆에 쌓인다. */
    @Test
    void 판이_바뀐_뒤_다시_동의하면_옛_줄은_두고_새_줄을_쌓는다() {
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.TOS, OLD));
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.PRIVACY, OLD));
        em.flush();

        termsService.agree(parent, ALL);
        em.flush();

        assertThat(termsAgreementRepository.findByUserId(parent.getId()))
                .extracting(TermsAgreement::getVersion)
                .containsExactlyInAnyOrder(OLD, OLD,
                        TermsCatalog.currentVersion(TermKey.TOS), TermsCatalog.currentVersion(TermKey.PRIVACY));
    }

    @Test
    void 필수_약관이_빠지면_저장하지_않는다() {
        assertInvalid(parent, List.of(TermKey.TOS));
    }

    @Test
    void 보호자가_기관_약관을_섞으면_저장하지_않는다() {
        assertInvalid(parent, List.of(TermKey.TOS, TermKey.PRIVACY, TermKey.ORG_TOS));
    }

    @Test
    void 기관_담당자는_기관_약관_셋에_동의하면_줄이_셋_생긴다() {
        User staff = staff("terms-staff");

        termsService.agree(staff, ORG_ALL);
        em.flush();

        assertThat(termsAgreementRepository.findByUserId(staff.getId()))
                .extracting(TermsAgreement::getTermKey, TermsAgreement::getVersion)
                .containsExactlyInAnyOrder(
                        tuple(TermKey.ORG_TOS, TermsCatalog.currentVersion(TermKey.ORG_TOS)),
                        tuple(TermKey.ORG_PRIVACY, TermsCatalog.currentVersion(TermKey.ORG_PRIVACY)),
                        tuple(TermKey.ORG_CHILD_DATA, TermsCatalog.currentVersion(TermKey.ORG_CHILD_DATA)));
        assertThat(termsService.isAgreed(staff)).isTrue();
    }

    @Test
    void 기관_담당자가_보호자_약관을_보내면_저장하지_않는다() {
        assertInvalid(staff("terms-staff-parent-keys"), ALL);
    }

    @Test
    void 기관_담당자가_서약을_빠뜨리면_저장하지_않는다() {
        assertInvalid(staff("terms-staff-missing"), List.of(TermKey.ORG_TOS, TermKey.ORG_PRIVACY));
    }

    @Test
    void 기록이_없는_보호자는_동의하지_않은_것이다() {
        assertThat(termsService.isAgreed(parent)).isFalse();
    }

    @Test
    void 기록이_없는_기관_담당자는_동의하지_않은_것이다() {
        assertThat(termsService.isAgreed(staff("terms-staff-2"))).isFalse();
    }

    /** 다른 역할의 약관 줄은 판정에 쓰지 않는다. */
    @Test
    void 기관_담당자에게_보호자_약관_줄만_있으면_동의하지_않은_것이다() {
        User staff = staff("terms-staff-3");
        em.persist(TermsAgreement.agree(staff.getId(), TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)));
        em.persist(TermsAgreement.agree(staff.getId(), TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));
        em.flush();

        assertThat(termsService.isAgreed(staff)).isFalse();
    }

    /** 기관 개인정보 수집 · 아동 정보 보호 서약은 판이 바뀌면 다시 받는다. */
    @Test
    void 기관_개인정보_수집이나_서약이_옛_판뿐이면_동의하지_않은_것이다() {
        User privacyOld = staff("terms-staff-4");
        agreeOrg(privacyOld, TermKey.ORG_PRIVACY);
        User childDataOld = staff("terms-staff-5");
        agreeOrg(childDataOld, TermKey.ORG_CHILD_DATA);
        em.flush();

        assertThat(termsService.isAgreed(privacyOld)).isFalse();
        assertThat(termsService.isAgreed(childDataOld)).isFalse();
    }

    /** 기관 이용약관은 한 번 동의했으면 판이 바뀌어도 통과한다. */
    @Test
    void 기관_이용약관이_옛_판뿐이어도_동의한_것이다() {
        User staff = staff("terms-staff-6");
        agreeOrg(staff, TermKey.ORG_TOS);
        em.flush();

        assertThat(termsService.isAgreed(staff)).isTrue();
    }

    @Test
    void 가입_미완료_회원은_동의하지_않은_것이다() {
        User pending = em.persist(User.pending("terms-pending", "박지현"));

        assertThat(termsService.isAgreed(pending)).isFalse();
    }

    /** 개인정보 수집은 판이 바뀌면 다시 받는다 — 옛 판 동의로는 통과하지 못한다. */
    @Test
    void 개인정보_수집이_옛_판뿐이면_동의하지_않은_것이다() {
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.TOS, TermsCatalog.currentVersion(TermKey.TOS)));
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.PRIVACY, OLD));
        em.flush();

        assertThat(termsService.isAgreed(parent)).isFalse();
    }

    /** 이용약관은 한 번 동의했으면 판이 바뀌어도 통과한다. */
    @Test
    void 이용약관이_옛_판뿐이어도_동의한_것이다() {
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.TOS, OLD));
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));
        em.flush();

        assertThat(termsService.isAgreed(parent)).isTrue();
    }

    @Test
    void 하나라도_없으면_동의하지_않은_것이다() {
        em.persist(TermsAgreement.agree(parent.getId(), TermKey.PRIVACY, TermsCatalog.currentVersion(TermKey.PRIVACY)));
        em.flush();

        assertThat(termsService.isAgreed(parent)).isFalse();
    }

    private User staff(String kakaoId) {
        User staff = em.persist(UserFixture.organizationUser(kakaoId, "박지현", 1L));
        em.flush();
        return staff;
    }

    /** 기관 약관 셋을 쌓되 {@code oldKey} 만 옛 판으로 둔다. */
    private void agreeOrg(User staff, TermKey oldKey) {
        for (TermKey key : ORG_ALL) {
            em.persist(TermsAgreement.agree(staff.getId(), key, key == oldKey ? OLD : TermsCatalog.currentVersion(key)));
        }
    }

    private void assertInvalid(User user, List<TermKey> agreed) {
        assertThatThrownBy(() -> termsService.agree(user, agreed))
                .isInstanceOfSatisfying(UserException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.INVALID_TERMS_AGREEMENT));
        assertThat(termsAgreementRepository.findByUserId(user.getId())).isEmpty();
    }
}
