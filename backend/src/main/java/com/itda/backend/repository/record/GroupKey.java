package com.itda.backend.repository.record;

import java.time.LocalDate;

/** 요약 묶음(아동·날짜)의 키. 기관은 조회 조건으로 따로 준다. */
public record GroupKey(Long childId, LocalDate entryDate) {
}
