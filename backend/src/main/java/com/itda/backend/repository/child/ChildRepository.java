package com.itda.backend.repository.child;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.child.Child;

public interface ChildRepository extends JpaRepository<Child, Long> {

    Optional<Child> findByIdAndDeletedAtIsNull(Long id);

    List<Child> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    /**
     * 보호자 연결 수락·해제, 동의 부여·철회, 사람의 매칭 확정용. 삭제된 아동도 잠근다 — 삭제 여부는 잠근 뒤 판단해야
     * 동시에 진행되는 삭제를 놓치지 않는다. 여러 행을 잠그는 작업은
     * child → child_organization → invitation → child_guardian → sharing_consent 순서를 지킨다 (DB 스키마 §3.3).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Child c where c.id = :id")
    Optional<Child> findByIdForUpdate(@Param("id") Long id);

    // FK/연관관계 없이(§16 원칙) child_organization을 평범한 Long 컬럼으로만 이어서 조회한다.
    // 이름 그대로 동의 필터가 없다 — 화면용 명부(O-10)는 pending_consent도
    // 같이 보여주고 화면에서 걸러야 하기 때문(api-spec.md O-11). AI 매칭 명부처럼 동의 없는
    // 아동을 절대 섞으면 안 되는 곳에서는 아래 findConsentedByOrganizationId를 써야 한다.
    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null")
    List<Child> findByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * AI 매칭 명부 전용. <b>이 기관에 대한</b> 살아 있는 동의가 있는 아이만 돌려준다 (DB 스키마 §5.1).
     *
     * <p>{@code child.status} 로 거르지 않는다. 그 값은 "어느 기관이든 동의가 있다"는 요약이라, 학교에만 동의한
     * 아이도 ACTIVE 다. status 로 거르면 동의하지 않은 기관의 명부에도 그 아이가 들어간다.
     */
    @Query("select c from Child c, ChildOrganization co "
            + "where co.childId = c.id and co.organizationId = :organizationId "
            + "and co.deletedAt is null and c.deletedAt is null "
            + "and exists (select sc.id from SharingConsent sc where sc.childId = c.id "
            + "and sc.organizationId = :organizationId "
            + "and sc.status = com.itda.backend.domain.consent.SharingConsentStatus.ACTIVE)")
    List<Child> findConsentedByOrganizationId(@Param("organizationId") Long organizationId);
}
