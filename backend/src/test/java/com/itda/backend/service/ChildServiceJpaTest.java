package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.domain.ChildStatus;
import com.itda.backend.domain.Organization;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.exception.ChildErrorCode;
import com.itda.backend.exception.ChildException;
import com.itda.backend.fixture.OrganizationFixture;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;

/** register 가 child 와 child_organization 을 실제로 저장해 O-10 명부에 나타나는지 DB 로 확인한다. */
@DataJpaTest
@ActiveProfiles("test")
@Import(ChildService.class)
class ChildServiceJpaTest {

    private static final String OUR_USER = "u-ours";
    private static final String OTHER_USER = "u-other";

    @Autowired
    private ChildService childService;

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private ChildOrganizationRepository childOrganizationRepository;

    @Autowired
    private TestEntityManager em;

    @MockitoBean
    private UserService userService;

    private Organization ours;
    private Organization other;

    @BeforeEach
    void setUp() {
        ours = em.persist(OrganizationFixture.center());
        other = em.persist(OrganizationFixture.center());
        given(userService.getOrganizationIdOf(OUR_USER)).willReturn(ours.getId());
        given(userService.getOrganizationIdOf(OTHER_USER)).willReturn(other.getId());
    }

    @Test
    void 등록한_아동은_우리_기관_명부에_동의_대기로_나타난다() {
        RegisteredChildResponse response = childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14", null));
        em.flush();
        em.clear();

        List<Child> roster = childRepository.findByOrganizationId(ours.getId());
        assertThat(roster).singleElement().satisfies(child -> {
            assertThat(String.valueOf(child.getId())).isEqualTo(response.child().id());
            assertThat(child.getName()).isEqualTo("김하늘");
            assertThat(child.getStatus()).isEqualTo(ChildStatus.PENDING_CONSENT);
        });
    }

    @Test
    void 등록한_아동은_다른_기관_명부에는_나타나지_않는다() {
        childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14", null));
        em.flush();
        em.clear();

        assertThat(childRepository.findByOrganizationId(other.getId())).isEmpty();
    }

    /** 중복 확인을 하지 않는다 — 같은 이름·생일이라도 새 아동으로 등록된다. */
    @Test
    void 같은_이름과_생일로_두_번_등록하면_두_명이_된다() {
        childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14", null));
        childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14", null));
        em.flush();
        em.clear();

        assertThat(childRepository.findByOrganizationId(ours.getId())).hasSize(2);
    }

    /** 다른 기관이 같은 아이를 등록해도 기존 아동을 찾지 않고 새 아동을 만든다. */
    @Test
    void 다른_기관이_같은_아이를_등록하면_별도_아동이_된다() {
        RegisteredChildResponse first = childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14", null));
        RegisteredChildResponse second = childService.register(OTHER_USER, new RegisterChildRequest("김하늘", "2017-03-14", null));

        assertThat(first.child().id()).isNotEqualTo(second.child().id());
    }

    private static RegisterChildRequest withExternalId(String externalId) {
        return new RegisterChildRequest("김하늘", "2017-03-14", externalId);
    }

    @Test
    void 같은_기관에서_같은_관리번호로_다시_등록하면_409다() {
        childService.register(OUR_USER, withExternalId("2026-0031"));
        em.flush();

        assertThatThrownBy(() -> childService.register(OUR_USER, withExternalId("2026-0031")))
                .isInstanceOfSatisfying(ChildException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChildErrorCode.DUPLICATE_EXTERNAL_ID));
    }

    @Test
    void 다른_기관은_같은_관리번호로_등록할_수_있다() {
        childService.register(OUR_USER, withExternalId("2026-0031"));
        childService.register(OTHER_USER, withExternalId("2026-0031"));
        em.flush();
        em.clear();

        assertThat(childRepository.findByOrganizationId(ours.getId())).hasSize(1);
        assertThat(childRepository.findByOrganizationId(other.getId())).hasSize(1);
    }

    @Test
    void 관리번호_없이는_여러_명을_등록할_수_있다() {
        childService.register(OUR_USER, withExternalId(null));
        childService.register(OUR_USER, withExternalId(null));
        em.flush();
        em.clear();

        assertThat(childRepository.findByOrganizationId(ours.getId())).hasSize(2);
    }

    @Test
    void 명부에서_뺀_아이의_관리번호는_새_아이에게_다시_줄_수_있다() {
        RegisteredChildResponse first = childService.register(OUR_USER, withExternalId("2026-0031"));
        ChildOrganization link = childOrganizationRepository
                .findByChildIdAndOrganizationId(Long.valueOf(first.child().id()), ours.getId())
                .orElseThrow();
        link.delete();
        em.flush();

        RegisteredChildResponse second = childService.register(OUR_USER, withExternalId("2026-0031"));

        assertThat(second.child().externalId()).isEqualTo("2026-0031");
        assertThat(second.child().id()).isNotEqualTo(first.child().id());
    }

    @Test
    void 명부_조회에_관리번호가_함께_나온다() {
        childService.register(OUR_USER, withExternalId("2026-0031"));
        childService.register(OUR_USER, withExternalId(null));
        em.flush();
        em.clear();

        List<ChildRosterResponse> roster = childService.getRoster(OUR_USER);

        assertThat(roster).extracting(ChildRosterResponse::externalId)
                .containsExactlyInAnyOrder("2026-0031", null);
    }
}
