package com.itda.backend.domain.child;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(name = "child")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Child extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private LocalDate birthdate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChildStatus status;

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

    public void activate() {
        this.status = ChildStatus.ACTIVE;
    }

    public void suspend() {
        this.status = ChildStatus.SUSPENDED;
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
}
