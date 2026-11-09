package com.itda.backend.repository.insight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;

/**
 * 인사이트 "마지막 회차" 쿼리의 계약 (#493). H2 단위 시험과 PostgreSQL 통합 시험이 같은 시험을 돈다 — 튜플 {@code IN} ·
 * {@code group by} 를 두 DB 가 같게 읽는지 본다.
 *
 * <ul>
 * <li>같은 (아이, 기간)의 마지막 회차에 든 인사이트는 <b>모두</b> 남는다 — 한 번 실행이 여러 행이 된다</li>
 * <li>상태는 마지막 회차를 고른 <b>뒤에</b> 건다 — 마지막 회차가 승인됐으면 옛 회차의 대기 행은 안 나온다</li>
 * <li>다른 기간 · 다른 아이의 회차와 섞이지 않는다</li>
 * </ul>
 */
// 시험 메서드가 이 클래스에 선언돼 있어, 하위 클래스의 @DataJpaTest 트랜잭션이 아니라 여기 선언을 본다
@Transactional
abstract class InsightLatestRevisionContract {

    private static final LocalDate W1 = LocalDate.of(2026, 8, 17);
    private static final LocalDate W2 = W1.plusWeeks(1);
    private static final LocalDate W3 = W1.plusWeeks(2);

    @Autowired
    protected TestEntityManager em;

    @Autowired
    protected InsightResultRepository repository;

    private InsightResult insight(Long childId, LocalDate from, int revision) {
        return em.persistAndFlush(InsightResult.of(childId, from, from.plusDays(6), revision, "상황", "반응", "본문",
                null, "[]", "[]", "[3]", false, null));
    }

    private void approve(InsightResult insight) {
        em.find(InsightResult.class, insight.getId()).approveGate2();
        em.flush();
    }

    private static List<Long> ids(List<InsightResult> insights) {
        return insights.stream().map(InsightResult::getId).toList();
    }

    private static List<Long> keyIds(List<InsightKey> keys) {
        return keys.stream().map(InsightKey::id).toList();
    }

    @Test
    void 마지막_회차에_든_인사이트는_모두_최신_것부터_나온다() {
        insight(1L, W1, 1);
        insight(1L, W1, 1);
        InsightResult first = insight(1L, W1, 2);
        InsightResult second = insight(1L, W1, 2);
        em.clear();

        assertThat(ids(repository.findLatestByChildIdInAndStatus(List.of(1L), InsightStatus.GATE2_PENDING)))
                .containsExactly(second.getId(), first.getId());
        assertThat(keyIds(repository.findLatestKeysByChildIdInAndStatus(List.of(1L), InsightStatus.GATE2_PENDING)))
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void 마지막_회차가_승인됐으면_옛_회차의_대기_행은_나오지_않는다() {
        insight(1L, W1, 1);
        InsightResult latest = insight(1L, W1, 2);
        approve(latest);
        em.clear();

        assertThat(repository.findLatestByChildIdInAndStatus(List.of(1L), InsightStatus.GATE2_PENDING)).isEmpty();
        assertThat(repository.findLatestKeysByChildIdInAndStatus(List.of(1L), InsightStatus.GATE2_PENDING)).isEmpty();
        assertThat(ids(repository.findLatestByChildIdInAndStatus(List.of(1L), InsightStatus.APPROVED)))
                .containsExactly(latest.getId());
    }

    @Test
    void 다른_기간이나_다른_아이의_회차와_섞이지_않는다() {
        InsightResult a1 = insight(1L, W1, 1);
        insight(1L, W2, 1);
        insight(1L, W2, 2);
        InsightResult a2 = insight(1L, W2, 3);
        InsightResult b1 = insight(2L, W1, 2);
        // 아이 2 의 W2 는 회차 5 까지 있다. 아이 1 의 W2 마지막 회차(3)를 가리면 안 된다
        insight(2L, W2, 4);
        InsightResult b2 = insight(2L, W2, 5);
        insight(3L, W1, 1);
        em.clear();

        assertThat(ids(repository.findLatestByChildIdInAndStatus(List.of(1L, 2L), InsightStatus.GATE2_PENDING)))
                .containsExactlyInAnyOrder(a1.getId(), a2.getId(), b1.getId(), b2.getId());
    }

    @Test
    void 엔티티_쿼리와_판정_칸_쿼리는_같은_행을_같은_순서로_준다() {
        insight(1L, W1, 1);
        InsightResult pending = insight(1L, W1, 2);
        InsightResult approved = insight(1L, W2, 1);
        approve(approved);
        insight(2L, W1, 1);
        InsightResult held = insight(2L, W1, 2);
        em.find(InsightResult.class, held.getId()).holdGate2();
        em.flush();
        em.clear();

        for (InsightStatus status : InsightStatus.values()) {
            assertThat(keyIds(repository.findLatestKeysByChildIdInAndStatus(List.of(1L, 2L), status)))
                    .as(status.name())
                    .containsExactlyElementsOf(ids(repository.findLatestByChildIdInAndStatus(List.of(1L, 2L), status)));
        }
        InsightKey key = repository.findLatestKeysByChildIdInAndStatus(List.of(1L), InsightStatus.GATE2_PENDING).get(0);
        assertThat(key).isEqualTo(new InsightKey(pending.getId(), 1L, W1, W1.plusDays(6), 2,
                InsightStatus.GATE2_PENDING, "[3]"));
    }

    /**
     * 기간 후보 쿼리는 아이 · 시작일 · 종료일을 따로 거른다. (1, W1) · (2, W2) 를 물으면 묻지 않은 (1, W2) 도 후보로 나올 수
     * 있다 — 정확한 조합은 부르는 쪽이 거른다. 여기서는 후보가 물은 값 안에 있는지와 각 기간이 마지막 회차인지만 본다.
     */
    @Test
    void 기간_후보는_물은_값_안의_마지막_회차만_준다() {
        insight(1L, W1, 1);
        InsightResult a1 = insight(1L, W1, 2);
        InsightResult a2 = insight(1L, W2, 1);
        insight(2L, W2, 1);
        InsightResult b2 = insight(2L, W2, 2);
        insight(1L, W3, 1);
        insight(3L, W1, 1);
        em.clear();

        List<InsightKey> candidates = repository.findLatestKeysForPeriods(Set.of(1L, 2L), Set.of(W1, W2),
                Set.of(W1.plusDays(6), W2.plusDays(6)));

        assertThat(candidates).allSatisfy(key -> {
            assertThat(key.childId()).isIn(1L, 2L);
            assertThat(key.periodFrom()).isIn(W1, W2);
        });
        assertThat(keyIds(candidates)).contains(a1.getId(), b2.getId())
                .isSubsetOf(a1.getId(), a2.getId(), b2.getId());
        assertThat(keyIds(candidates)).isSortedAccordingTo((x, y) -> Long.compare(y, x));
    }
}
