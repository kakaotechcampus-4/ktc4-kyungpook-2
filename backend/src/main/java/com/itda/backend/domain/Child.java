package com.itda.backend.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기관에 등록된 아동. 어느 기관에 다니는지는 이 테이블이 아니라 {@code child_organization} 이 가진다.
 *
 * <p>{@code status} 와 {@code deletedAt} 은 다른 것이다 — {@code SUSPENDED} 는 동의가 없어 처리를
 * 멈춘 상태이고, {@code deletedAt} 은 기관이 목록에서 뺀 상태다 (DB 스키마 §5.1).
 */
@Entity
@Table(name = "child")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Child {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** AI 매칭 명부(RosterEntry)의 필수값이라 비어 있으면 그 아이는 매칭 대상에서 빠진다. */
    @Column(nullable = false)
    private LocalDate birthdate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChildStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private Child(String name, LocalDate birthdate) {
        this.name = name;
        this.birthdate = birthdate;
        this.status = ChildStatus.PENDING_CONSENT;
    }

    public static Child of(String name, LocalDate birthdate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("아동 이름은 필수입니다.");
        }
        if (birthdate == null) {
            throw new IllegalArgumentException("아동 생년월일은 필수입니다.");
        }
        return new Child(name, birthdate);
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
