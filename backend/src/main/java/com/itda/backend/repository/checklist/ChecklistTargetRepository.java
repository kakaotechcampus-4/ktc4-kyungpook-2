package com.itda.backend.repository.checklist;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.checklist.ChecklistTarget;

public interface ChecklistTargetRepository extends JpaRepository<ChecklistTarget, Long> {

    List<ChecklistTarget> findByChecklistIdIn(Collection<Long> checklistIds);

    /**
     * 이 아이가 받은 요청 중 보호자에게 보이는 것 전부, 보낸 시각 최신순. 내리지 않았고 보낸 기관 명부에 아이가 <b>지금</b>
     * 있어야 한다. 아이가 삭제되지 않았는지는 부르는 쪽이 먼저 본다. 확인 여부와 상관없이 한 번에 읽는다 — 두 번 나눠 읽으면
     * 그 사이 다른 보호자가 확인한 요청이 양쪽에 같이 잡힌다.
     */
    @Query("select new com.itda.backend.repository.checklist.GuardianChecklistRow(t, c) "
            + "from ChecklistTarget t, Checklist c, ChildOrganization co "
            + "where t.childId = :childId and c.id = t.checklistId and c.canceledAt is null "
            + "and co.childId = t.childId and co.organizationId = c.organizationId and co.deletedAt is null "
            + "order by c.createdAt desc, c.id desc")
    List<GuardianChecklistRow> findVisibleForChild(@Param("childId") Long childId);

    /**
     * 위와 같은 조건에서 아직 아무도 확인하지 않은 것만. 벨 · 설정 · 알림 화면이 화면마다 부르는 경로라 쌓여 가는 확인 완료
     * 이력은 읽지 않는다.
     */
    @Query("select new com.itda.backend.repository.checklist.GuardianChecklistRow(t, c) "
            + "from ChecklistTarget t, Checklist c, ChildOrganization co "
            + "where t.childId = :childId and t.confirmedAt is null and c.id = t.checklistId and c.canceledAt is null "
            + "and co.childId = t.childId and co.organizationId = c.organizationId and co.deletedAt is null "
            + "order by c.createdAt desc, c.id desc")
    List<GuardianChecklistRow> findUnconfirmedForChild(@Param("childId") Long childId);

    /**
     * 확인할 줄의 id 하나만 고른다. 엔티티를 미리 읽어 두면 확인 UPDATE 뒤 다시 물어도 영속성 컨텍스트가 UPDATE 전 값을
     * 돌려준다.
     */
    @Query("select t.id from ChecklistTarget t where t.checklistId = :checklistId and t.childId = :childId")
    Optional<Long> findIdByChecklistIdAndChildId(@Param("checklistId") Long checklistId, @Param("childId") Long childId);

    /**
     * 확인을 찍는다. <b>처음 한 번만</b> — 엄마 · 아빠가 동시에 눌러도 뒤의 UPDATE 가 앞선 커밋을 기다렸다 조건을 다시 보고
     * 0행이 된다. 그래서 0행은 "이미 누가 확인했다" 이다. 부르는 쪽은 다시 읽은 값으로 응답한다. 요청이 내려지지 않았는지는
     * 부르는 쪽이 요청 행을 공유 잠금으로 잡은 채 먼저 본다({@code ChecklistRepository.findByIdForShare}).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChecklistTarget t set t.confirmedAt = :at, t.confirmedBy = :userId "
            + "where t.id = :id and t.confirmedAt is null")
    int confirmIfUnconfirmed(@Param("id") Long id, @Param("userId") Long userId, @Param("at") LocalDateTime at);
}
