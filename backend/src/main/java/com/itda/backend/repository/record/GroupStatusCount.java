package com.itda.backend.repository.record;

import java.time.LocalDate;

import com.itda.backend.domain.record.JournalEntryStatus;

/** 한 묶음(아동·날짜)에서 그 상태인 일지 수. 기관은 조회 조건으로 따로 준다. */
public record GroupStatusCount(Long childId, LocalDate entryDate, JournalEntryStatus status, long count) {
}
