package com.itda.backend.repository.record;

import java.time.LocalDate;

/** 그 날짜의 일지 수. 기관과 상태는 조회 조건으로 따로 준다. */
public record DateCount(LocalDate entryDate, long count) {
}
