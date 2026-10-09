package com.itda.backend.repository;

import java.time.LocalDate;

/** 매칭·검증이 아직 끝나지 않은 일지가 있는 기관·날짜. 매칭 전 일지는 아동을 몰라 기관·날짜로만 본다. */
public record InProgressKey(String institutionId, LocalDate entryDate) {
}
