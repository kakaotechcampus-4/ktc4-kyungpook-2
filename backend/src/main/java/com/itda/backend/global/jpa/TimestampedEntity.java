package com.itda.backend.global.jpa;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import lombok.Getter;

/** 처음 저장한 시각과 마지막으로 고친 시각을 남기는 엔티티의 부모. */
@Getter
@MappedSuperclass
public abstract class TimestampedEntity {

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = CreatedAtEntity.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = CreatedAtEntity.now();
    }
}
