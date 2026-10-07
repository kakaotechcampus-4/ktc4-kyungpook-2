package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.itda.backend.domain.ChildStatus;
import com.itda.backend.domain.Organization;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.fixture.OrganizationFixture;
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
        RegisteredChildResponse response = childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14"));
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
        childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14"));
        em.flush();
        em.clear();

        assertThat(childRepository.findByOrganizationId(other.getId())).isEmpty();
    }

    /** 중복 확인을 하지 않는다 — 같은 이름·생일이라도 새 아동으로 등록된다. */
    @Test
    void 같은_이름과_생일로_두_번_등록하면_두_명이_된다() {
        childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14"));
        childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14"));
        em.flush();
        em.clear();

        assertThat(childRepository.findByOrganizationId(ours.getId())).hasSize(2);
    }

    /** 다른 기관이 같은 아이를 등록해도 기존 아동을 찾지 않고 새 아동을 만든다. */
    @Test
    void 다른_기관이_같은_아이를_등록하면_별도_아동이_된다() {
        RegisteredChildResponse first = childService.register(OUR_USER, new RegisterChildRequest("김하늘", "2017-03-14"));
        RegisteredChildResponse second = childService.register(OTHER_USER, new RegisterChildRequest("김하늘", "2017-03-14"));

        assertThat(first.child().id()).isNotEqualTo(second.child().id());
    }
}
