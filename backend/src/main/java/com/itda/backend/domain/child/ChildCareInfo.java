package com.itda.backend.domain.child;

import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.itda.backend.global.jpa.TimestampedEntity;

@Entity
@Table(
        name = "child_care_info",
        uniqueConstraints = @UniqueConstraint(name = "uk_child_care_info_child", columnNames = "child_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChildCareInfo extends TimestampedEntity {

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

    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;

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

    public record Medication(String name, String time) {
    }
}
