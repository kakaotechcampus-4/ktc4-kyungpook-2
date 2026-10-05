package com.itda.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.Child;

public interface ChildRepository extends JpaRepository<Child, Long> {

    Optional<Child> findByIdAndDeletedAtIsNull(Long id);

    // FK/연관관계 없이(§16 원칙) child_organization을 평범한 Long 컬럼으로만 이어서 조회한다.
    // 이름 그대로 상태(ChildStatus) 필터가 없다 — 화면용 명부(O-10)는 pending_consent도
    // 같이 보여주고 화면에서 걸러야 하기 때문(api-spec.md O-11). AI 매칭 명부처럼 동의 전
    // 아동을 절대 섞으면 안 되는 곳에서는 아래 findActiveByOrganizationId를 써야 한다.
    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null")
    List<Child> findByOrganizationId(@Param("organizationId") Long organizationId);

    // AI 매칭 명부 전용 — status = ACTIVE(동의 완료)까지 걸러서, 동의 전 아동이
    // 매칭 대상으로 새는 걸 이름만으로는 못 막던 문제를 고친다.
    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null and c.status = com.itda.backend.domain.ChildStatus.ACTIVE")
    List<Child> findActiveByOrganizationId(@Param("organizationId") Long organizationId);
}
