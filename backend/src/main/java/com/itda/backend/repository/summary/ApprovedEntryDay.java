package com.itda.backend.repository.summary;

import java.time.LocalDate;

/** Gate 1 을 통과한 요약 하나가 가리키는 (아이, 관찰 날짜). 인사이트 워커가 어느 주를 볼지 고를 때 쓴다. */
public record ApprovedEntryDay(Long childId, LocalDate entryDate) {
}
