package com.itda.backend.repository.checklist;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.checklist.ChecklistTarget;

public interface ChecklistTargetRepository extends JpaRepository<ChecklistTarget, Long> {

    List<ChecklistTarget> findByChecklistIdIn(Collection<Long> checklistIds);
}
