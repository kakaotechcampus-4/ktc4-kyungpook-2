package com.itda.backend.service.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildCareInfo;
import com.itda.backend.domain.child.ChildCareInfo.Medication;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest;
import com.itda.backend.dto.request.child.UpdateCareInfoRequest.MedicationRequest;
import com.itda.backend.dto.response.child.ChildCareInfoResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.service.user.UserService;

/**
 * 돌봄 정보 저장이 실제 DB 행에 무엇을 남기는지 본다. 엄마·아빠 모두 하늘이와 연결돼 있다. 바다는 엄마와만 연결돼 있다.
 * 저장한 뒤에는 영속성 컨텍스트를 비우고 다시 읽어 JSON 칸에 실제로 들어간 값을 본다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(ChildCareInfoService.class)
class ChildCareInfoServiceJpaTest {

    private static final String MOM_PRINCIPAL = "41";
    private static final String DAD_PRINCIPAL = "52";
    private static final Long MOM = 41L;
    private static final Long DAD = 52L;
    private static final Long INVITATION = 1L;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ChildCareInfoService service;

    @MockitoBean
    private UserService userService;

    private Long sky;
    private Long sea;

    @BeforeEach
    void setUp() {
        sky = em.persistAndFlush(Child.of("김하늘", LocalDate.of(2017, 3, 14))).getId();
        sea = em.persistAndFlush(Child.of("이바다", LocalDate.of(2019, 5, 1))).getId();
        em.persistAndFlush(ChildGuardian.link(sky, MOM, INVITATION));
        em.persistAndFlush(ChildGuardian.link(sky, DAD, INVITATION));
        em.persistAndFlush(ChildGuardian.link(sea, MOM, INVITATION));

        given(userService.getParentIdOf(MOM_PRINCIPAL)).willReturn(MOM);
        given(userService.getParentIdOf(DAD_PRINCIPAL)).willReturn(DAD);
        em.clear();
    }

    @Test
    void 처음_저장하면_행이_하나_생긴다() {
        ChildCareInfoResponse saved = service.save(MOM_PRINCIPAL, sky,
                request(List.of("우유와 유제품", "땅콩"), new MedicationRequest("○○정", "점심 식후")));
        flushAndClear();

        assertThat(saved.allergies()).containsExactly("우유와 유제품", "땅콩");
        ChildCareInfo stored = storedOf(sky);
        assertThat(stored.getAllergies()).containsExactly("우유와 유제품", "땅콩");
        assertThat(stored.getMedications()).containsExactly(new Medication("○○정", "점심 식후"));
        assertThat(stored.getUpdatedBy()).isEqualTo(MOM);
        assertThat(rowCount()).isEqualTo(1);
    }

    /** 아이마다 한 행이다. 아빠가 다시 저장하면 엄마가 저장한 행을 덮어쓰고, 마지막에 고친 사람이 바뀐다. */
    @Test
    void 다시_저장하면_같은_행을_덮어쓴다() {
        service.save(MOM_PRINCIPAL, sky, request(List.of("땅콩"), new MedicationRequest("○○정", "점심 식후")));
        flushAndClear();

        service.save(DAD_PRINCIPAL, sky, request(List.of("계란", "갑각류"), new MedicationRequest("△△시럽", "")));
        flushAndClear();

        ChildCareInfo stored = storedOf(sky);
        assertThat(stored.getAllergies()).containsExactly("계란", "갑각류");
        assertThat(stored.getMedications()).extracting(Medication::name, Medication::time)
                .containsExactly(tuple("△△시럽", ""));
        assertThat(stored.getUpdatedBy()).isEqualTo(DAD);
        assertThat(rowCount()).isEqualTo(1);
    }

    /** 지우기도 덮어쓰기다. 빈 배열로 덮어쓰면 다시 읽어도 비어 있다. */
    @Test
    void 두_목록을_모두_비워_저장할_수_있다() {
        service.save(MOM_PRINCIPAL, sky, request(List.of("땅콩"), new MedicationRequest("○○정", "점심 식후")));
        flushAndClear();

        service.save(DAD_PRINCIPAL, sky, new UpdateCareInfoRequest(List.of(), List.of()));
        flushAndClear();

        ChildCareInfo stored = storedOf(sky);
        assertThat(stored.getAllergies()).isEmpty();
        assertThat(stored.getMedications()).isEmpty();
        assertThat(stored.getUpdatedBy()).isEqualTo(DAD);
    }

    @Test
    void 다른_아이의_돌봄_정보는_건드리지_않는다() {
        service.save(MOM_PRINCIPAL, sky, request(List.of("땅콩")));
        service.save(MOM_PRINCIPAL, sea, request(List.of("계란")));
        flushAndClear();

        assertThat(storedOf(sky).getAllergies()).containsExactly("땅콩");
        assertThat(storedOf(sea).getAllergies()).containsExactly("계란");
    }

    // --- 권한 ---

    @Test
    void 연결되지_않은_아이는_403이고_행이_생기지_않는다() {
        assertForbidden(() -> service.save(DAD_PRINCIPAL, sea, request(List.of("땅콩"))));
        flushAndClear();

        assertThat(rowCount()).isZero();
    }

    @Test
    void 끊긴_연결의_아이는_403이고_저장된_값이_그대로다() {
        service.save(MOM_PRINCIPAL, sky, request(List.of("땅콩")));
        flushAndClear();
        guardianOf(sky, DAD).delete();
        em.flush();
        em.clear();

        assertForbidden(() -> service.save(DAD_PRINCIPAL, sky, request(List.of("계란"))));
        flushAndClear();

        ChildCareInfo stored = storedOf(sky);
        assertThat(stored.getAllergies()).containsExactly("땅콩");
        assertThat(stored.getUpdatedBy()).isEqualTo(MOM);
    }

    @Test
    void 삭제된_아이는_403() {
        em.find(Child.class, sky).delete();
        em.flush();
        em.clear();

        assertForbidden(() -> service.save(MOM_PRINCIPAL, sky, request(List.of("땅콩"))));
    }

    @Test
    void 없는_아이는_403() {
        assertForbidden(() -> service.save(MOM_PRINCIPAL, 999_999L, request(List.of("땅콩"))));
    }

    // --- 도구 ---

    private static UpdateCareInfoRequest request(List<String> allergies, MedicationRequest... medications) {
        return new UpdateCareInfoRequest(allergies, List.of(medications));
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private ChildCareInfo storedOf(Long childId) {
        return em.getEntityManager()
                .createQuery("select c from ChildCareInfo c where c.childId = :childId", ChildCareInfo.class)
                .setParameter("childId", childId)
                .getSingleResult();
    }

    private long rowCount() {
        return em.getEntityManager().createQuery("select count(c) from ChildCareInfo c", Long.class).getSingleResult();
    }

    private ChildGuardian guardianOf(Long childId, Long userId) {
        return em.getEntityManager()
                .createQuery("select g from ChildGuardian g where g.childId = :childId and g.userId = :userId",
                        ChildGuardian.class)
                .setParameter("childId", childId)
                .setParameter("userId", userId)
                .getSingleResult();
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ChildException.class)
                .extracting(e -> ((ChildException) e).getErrorCode())
                .isEqualTo(ChildErrorCode.CHILD_NOT_LINKED);
    }
}
