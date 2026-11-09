package com.itda.backend.repository.checklist;

import com.itda.backend.domain.checklist.Checklist;
import com.itda.backend.domain.checklist.ChecklistTarget;

public record GuardianChecklistRow(ChecklistTarget target, Checklist checklist) {
}
