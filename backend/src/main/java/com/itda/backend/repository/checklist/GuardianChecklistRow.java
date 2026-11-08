package com.itda.backend.repository.checklist;

import com.itda.backend.domain.checklist.Checklist;
import com.itda.backend.domain.checklist.ChecklistTarget;

/** 보호자에게 보이는 준비물·확인사항 한 줄 — 아이 몫의 확인과 보낸 내용. */
public record GuardianChecklistRow(ChecklistTarget target, Checklist checklist) {
}
