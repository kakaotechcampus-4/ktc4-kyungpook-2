package com.itda.backend.global.jpa;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import lombok.Getter;

/** 처음 저장한 시각만 남기는 엔티티의 부모. 고치지 않는 행(기록 · 이력)이 쓴다. */
@Getter
@MappedSuperclass
public abstract class CreatedAtEntity {

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = now();
    }

    /**
     * 리눅스 시계는 나노초까지 주는데 DB 는 마이크로초까지만 저장한다. 미리 잘라 두면 저장한 엔티티의 값과 DB 에서 다시 읽은 값이
     * 같다 — 같은 요청을 다시 보냈을 때 처음 응답과 시각이 어긋나지 않는다.
     */
    static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
