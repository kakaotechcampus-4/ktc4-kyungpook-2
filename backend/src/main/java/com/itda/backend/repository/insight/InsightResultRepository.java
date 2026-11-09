package com.itda.backend.repository.insight;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.insight.InsightResult;
import com.itda.backend.domain.insight.InsightStatus;

public interface InsightResultRepository extends JpaRepository<InsightResult, Long> {

    /**
     * 그 아이들의 인사이트에 (아이, 기간)마다 가장 큰 회차 번호({@code latest})를 붙인 표. {@code latest = revision} 인 행이
     * 마지막 회차에 든 인사이트다 — 한 회차에 여러 건이면 모두 남는다.
     *
     * <p>창 함수로 그 아이들의 행을 <b>한 번만</b> 훑는다. 같은 데이터(아이 40명 · 인사이트 9,360건, PostgreSQL 16)에서 견줬다
     * (#493): 행마다 최대 회차를 다시 구하는 상관 서브쿼리는 서브쿼리가 후보 행 수만큼 돌아 버퍼 4,304, 그룹 최대를 따로 구해
     * 튜플 {@code IN} 으로 맞추면 두 번 훑어 2,152 (주 후보로 좁히면 Merge Join 이 되풀이 읽어 5,608), 이 방식은 1,076 — 고치기
     * 전에 이력을 통째로 읽던 것과 같은 양이다. {@code (child_id, period_from, period_to, revision)} 인덱스를 더해도 주 후보
     * 쿼리가 1,459 라 인덱스는 두지 않는다.
     *
     * <p><b>상태는 이 표 바깥에서 건다.</b> 최대 회차는 상태와 상관없이 정한다 — 상태로 먼저 좁히면 최신 회차가 이미 승인된
     * 경우 옛 회차의 대기 행이 최신으로 보인다.
     */
    String LATEST_OF_CHILDREN = "(select r.id as id, r.childId as childId, r.periodFrom as periodFrom, "
            + "r.periodTo as periodTo, r.revision as revision, r.status as status, "
            + "r.sourceInstitutionIds as sourceInstitutionIds, "
            + "max(r.revision) over (partition by r.childId, r.periodFrom, r.periodTo) as latest "
            + "from InsightResult r where r.childId in :childIds) x";

    /**
     * {@link #LATEST_OF_CHILDREN} 를 주어진 시작일 · 종료일 후보로 좁힌 것. 기간은 창의 나눔 기준이라, 걸러도 한 기간의 행은
     * 통째로 남거나 통째로 빠진다 — 남은 기간의 최대 회차는 그대로다.
     */
    String LATEST_OF_PERIODS = "(select r.id as id, r.childId as childId, r.periodFrom as periodFrom, "
            + "r.periodTo as periodTo, r.revision as revision, r.status as status, "
            + "r.sourceInstitutionIds as sourceInstitutionIds, "
            + "max(r.revision) over (partition by r.childId, r.periodFrom, r.periodTo) as latest "
            + "from InsightResult r where r.childId in :childIds "
            + "and r.periodFrom in :periodFroms and r.periodTo in :periodTos) x";

    String KEY = "new com.itda.backend.repository.insight.InsightKey("
            + "x.id, x.childId, x.periodFrom, x.periodTo, x.revision, x.status, x.sourceInstitutionIds)";

    /**
     * Gate 2 목록. 그 아이들의 마지막 회차 중 이 상태인 인사이트, 최신 것부터. 회차 고르기와 본문 읽기를 한 SQL 문장으로 한다 —
     * 따로 읽으면 그 사이 승인이 커밋돼 대기 목록에 승인된 글이 섞일 수 있다(READ COMMITTED 는 문장마다 새로 본다).
     */
    @Query("select i from InsightResult i where i.id in (select x.id from " + LATEST_OF_CHILDREN
            + " where x.revision = x.latest and x.status = :status) order by i.id desc")
    List<InsightResult> findLatestByChildIdInAndStatus(@Param("childIds") Collection<Long> childIds,
            @Param("status") InsightStatus status);

    /** {@link #findLatestByChildIdInAndStatus} 와 같은 행 · 같은 순서로 판정에 쓰는 칸만. 대시보드의 대기 수가 쓴다. */
    @Query("select " + KEY + " from " + LATEST_OF_CHILDREN
            + " where x.revision = x.latest and x.status = :status order by x.id desc")
    List<InsightKey> findLatestKeysByChildIdInAndStatus(@Param("childIds") Collection<Long> childIds,
            @Param("status") InsightStatus status);

    /**
     * 주간 단계 후보. 아이 · 시작일 · 종료일을 <b>따로</b> 거르므로 요청하지 않은 조합(A 의 첫째 주와 B 의 둘째 주를 요청하면
     * A 의 둘째 주)도 섞일 수 있다 — 정확한 (아이, 주)는 부르는 쪽이 거른다. 각 기간은 마지막 회차만, 최신 것부터다.
     */
    @Query("select " + KEY + " from " + LATEST_OF_PERIODS + " where x.revision = x.latest order by x.id desc")
    List<InsightKey> findLatestKeysForPeriods(@Param("childIds") Collection<Long> childIds,
            @Param("periodFroms") Collection<LocalDate> periodFroms,
            @Param("periodTos") Collection<LocalDate> periodTos);

    /** 상태별 인사이트 전부. 조회 API 는 {@link #findLatestByChildIdInAndStatus} 를 쓰고, 여기서는 워커 테스트가 쓴다. */
    List<InsightResult> findByStatusOrderByIdDesc(InsightStatus status);

    /**
     * 그 아이들의 인사이트 전부, 지난 회차까지. 회차와 상관없이 다 봐야 하는 곳(열람 권한 거두기 · 특이사항 없는 주 고르기)이
     * 쓴다. 마지막 회차만 필요하면 {@link #findLatestByChildIdInAndStatus} · {@link #findLatestKeysForPeriods} 를 쓴다.
     */
    List<InsightResult> findByChildIdInOrderByIdDesc(Collection<Long> childIds);

    /**
     * 그 아이의 한 상태 인사이트, 오래된 순. 새로 연결된 보호자에게 승인된 글을 열 때 쓴다 ({@code InsightAccessGranter}).
     * 오래된 글부터 넣어 권한 행 id 도 오래된 순이 되게 한다.
     */
    List<InsightResult> findByChildIdAndStatusOrderByIdAsc(Long childId, InsightStatus status);

    /**
     * 그 아이·그 기간의 마지막 회차 번호. 옛 회차를 승인해 철 지난 글이 다른 기관으로 나가는 것을 막는 데 쓴다.
     * 인사이트가 한 건도 없으면 {@code null} 이다.
     */
    @Query("select max(i.revision) from InsightResult i "
            + "where i.childId = :childId and i.periodFrom = :periodFrom and i.periodTo = :periodTo")
    Integer findMaxRevision(@Param("childId") Long childId,
            @Param("periodFrom") LocalDate periodFrom, @Param("periodTo") LocalDate periodTo);

    /**
     * Gate 2 결정용 잠금 조회. 같은 인사이트에 승인과 보류가 동시에 오면 한쪽만 반영되게 한다 —
     * 잠그지 않으면 둘 다 {@code GATE2_PENDING} 을 읽고 둘 다 성공한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InsightResult i where i.id = :id")
    Optional<InsightResult> findByIdForUpdate(@Param("id") Long id);

    /** 그 아이들의 {@code since} 뒤로 만든 인사이트. 근거 기관이 JSON 글이라 기관으로는 자바에서 거른다 (#370). */
    List<InsightResult> findByChildIdInAndCreatedAtGreaterThanEqual(Collection<Long> childIds, LocalDateTime since);

    /**
     * 그때 이후 Gate 2 를 통과한 인사이트들의 (아이, 기간). 알림 워커가 <b>어느 기간을 보낼지</b> 고른다 (#419).
     *
     * <p>기간이 아니라 <b>승인 시각</b>으로 고른다. 교사가 밀린 인사이트를 늦게 승인해도 그 기간을 보내려고다 —
     * {@code ChildContextRepository.findApprovedEntryDaysSince} 와 같은 이유다.
     *
     * <p>{@code updatedAt} 을 본다. 인사이트는 워커가 만들 때 {@code createdAt} 이 찍히고, 교사가 승인할 때
     * {@code updatedAt} 이 갱신된다 — 만든 시각으로 고르면 늦은 승인을 놓친다.
     */
    @Query("select distinct new com.itda.backend.repository.insight.ApprovedInsightPeriod("
            + "i.childId, i.periodFrom, i.periodTo) "
            + "from InsightResult i where i.status = :status and i.updatedAt >= :since")
    List<ApprovedInsightPeriod> findApprovedPeriodsSince(@Param("status") InsightStatus status,
            @Param("since") LocalDateTime since);
}
