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

    @Query("select new com.itda.backend.repository.checklist.GuardianChecklistRow(t, c) "
            + "from ChecklistTarget t, Checklist c, ChildOrganization co "
            + "where t.childId = :childId and c.id = t.checklistId and c.canceledAt is null "
            + "and co.childId = t.childId and co.organizationId = c.organizationId and co.deletedAt is null "
            + "order by c.createdAt desc, c.id desc")
    List<GuardianChecklistRow> findVisibleForChild(@Param("childId") Long childId);

    @Query("select new com.itda.backend.repository.checklist.GuardianChecklistRow(t, c) "
            + "from ChecklistTarget t, Checklist c, ChildOrganization co "
            + "where t.childId = :childId and t.confirmedAt is null and c.id = t.checklistId and c.canceledAt is null "
            + "and co.childId = t.childId and co.organizationId = c.organizationId and co.deletedAt is null "
            + "order by c.createdAt desc, c.id desc")
    List<GuardianChecklistRow> findUnconfirmedForChild(@Param("childId") Long childId);

    @Query("select t.id from ChecklistTarget t where t.checklistId = :checklistId and t.childId = :childId")
    Optional<Long> findIdByChecklistIdAndChildId(@Param("checklistId") Long checklistId, @Param("childId") Long childId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChecklistTarget t set t.confirmedAt = :at, t.confirmedBy = :userId "
            + "where t.id = :id and t.confirmedAt is null")
    int confirmIfUnconfirmed(@Param("id") Long id, @Param("userId") Long userId, @Param("at") LocalDateTime at);
}
