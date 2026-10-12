package com.itda.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;

/**
 * 기관 연결 저장이 실패하면 먼저 저장한 아동도 남지 않아야 한다.
 *
 * <p>테스트 트랜잭션을 끈다(NOT_SUPPORTED). 그래야 {@code ChildService.register} 의 트랜잭션이 가장 바깥이 되어
 * 실제 롤백을 확인할 수 있다. 트랜잭션이 없으니 TestEntityManager 는 쓰지 않고, 남은 행은 Repository 로 정리한다.
 *
 * <p>연결 저장소는 Spring Data 프록시에 spy 를 거는 대신 통째로 mock 으로 바꿔 실패 지점을 확실히 한다.
 * 그래서 이 테스트만 별도 클래스다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(ChildService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChildServiceRollbackTest {

    private static final String USER_ID = "u-rollback";
    private static final String CHILD_NAME = "롤백검증아동";

    @Autowired
    private ChildService childService;

    @Autowired
    private ChildRepository childRepository;

    @MockitoBean
    private ChildOrganizationRepository childOrganizationRepository;

    @MockitoBean
    private UserService userService;

    @AfterEach
    void cleanUp() {
        childRepository.deleteAll(childrenNamedForThisTest());
    }

    @Test
    void 기관_연결_저장에_실패하면_아동도_롤백된다() {
        given(userService.getOrganizationIdOf(USER_ID)).willReturn(1L);
        given(childOrganizationRepository.saveAndFlush(any(ChildOrganization.class)))
                .willThrow(new IllegalStateException("연결 저장 실패"));

        assertThatThrownBy(() -> childService.register(USER_ID, new RegisterChildRequest(CHILD_NAME, "2017-03-14", null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(childrenNamedForThisTest()).isEmpty();
    }

    private List<Child> childrenNamedForThisTest() {
        return childRepository.findAll().stream()
                .filter(child -> CHILD_NAME.equals(child.getName()))
                .toList();
    }
}
