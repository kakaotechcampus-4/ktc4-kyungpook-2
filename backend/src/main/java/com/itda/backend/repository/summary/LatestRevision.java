package com.itda.backend.repository.summary;

import java.time.LocalDate;

/** 한 묶음(아동·날짜)의 가장 큰 판 번호. */
public record LatestRevision(Long childId, LocalDate entryDate, Integer revision) {
}
