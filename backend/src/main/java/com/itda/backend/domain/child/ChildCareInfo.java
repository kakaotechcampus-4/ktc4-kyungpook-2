package com.itda.backend.domain.child;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보호자가 적어 두는 아이의 돌봄 정보 — 알레르기와 복용약 (DB 스키마 §5.7, api-spec G-13).
 *
 * <p><b>현재 이 아이와 연결된 모든 보호자가 조회·수정할 수 있고, 기관에는 공유하지 않는다.</b> 알레르기·복용약은 아이에
 * 대한 사실이라 엄마·아빠가 다른 값을 가지면 안 되므로 아이마다 한 행이고, 나중에 저장한 쪽이 덮어쓴다.
 *
 * <p>지우지 않는다. 저장은 늘 통째로 덮어쓰고, 비우는 것도 빈 배열로 덮어쓰는 것이다. 보호자 연결이 끊겨도 남은 보호자가
 * 계속 보므로 그대로 둔다. 그래서 {@code deleted_at} 이 없다.
 *
 * <p>두 목록은 JSON 문자열로 TEXT 에 넣는다 — 늘 통째로 읽고 쓰고, 항목 하나로 찾을 일이 없다 (동의 범위와 같다).
 */
@Entity
@Table(
        name = "child_care_info",
        uniqueConstraints = @UniqueConstraint(name = "uk_child_care_info_child", columnNames = "child_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildCareInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Convert(converter = AllergiesConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<String> allergies;

    @Convert(converter = MedicationsConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private List<Medication> medications;

    /** 마지막으로 고친 보호자 {@code users.id} */
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private ChildCareInfo(Long childId, List<String> allergies, List<Medication> medications, Long updatedBy) {
        this.childId = childId;
        this.allergies = List.copyOf(allergies);
        this.medications = List.copyOf(medications);
        this.updatedBy = updatedBy;
    }

    public static ChildCareInfo of(Long childId, List<String> allergies, List<Medication> medications,
            Long updatedBy) {
        if (childId == null) {
            throw new IllegalArgumentException("대상 아동 ID는 필수입니다.");
        }
        requireContents(allergies, medications, updatedBy);
        return new ChildCareInfo(childId, allergies, medications, updatedBy);
    }

    /** 통째로 덮어쓴다. 부분 수정은 없다. */
    public void replace(List<String> allergies, List<Medication> medications, Long updatedBy) {
        requireContents(allergies, medications, updatedBy);
        this.allergies = List.copyOf(allergies);
        this.medications = List.copyOf(medications);
        this.updatedBy = updatedBy;
    }

    private static void requireContents(List<String> allergies, List<Medication> medications, Long updatedBy) {
        if (allergies == null || medications == null) {
            throw new IllegalArgumentException("알레르기·복용약 목록은 비어 있어도 null 일 수는 없습니다.");
        }
        if (updatedBy == null) {
            throw new IllegalArgumentException("고친 보호자 ID는 필수입니다.");
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 복용약 하나.
     *
     * @param time 복용 시간 문구(예: "점심 식후"). 없으면 빈 문자열
     */
    public record Medication(String name, String time) {
    }
}
