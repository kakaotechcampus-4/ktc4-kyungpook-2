package com.itda.backend.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.itda.backend.domain.ChildOrganization;

public interface ChildOrganizationRepository extends JpaRepository<ChildOrganization, Long> {

    boolean existsByChildIdAndOrganizationIdAndDeletedAtIsNull(Long childId, Long organizationId);

    /** 재연결 전용. 연결 해제된 행도 찾아서 되살릴 수 있게 {@code deletedAt} 조건을 걸지 않는다. */
    Optional<ChildOrganization> findByChildIdAndOrganizationId(Long childId, Long organizationId);

    /**
     * 보호자 연결 수락용. 명부에서 빠진 행도 잠근다 — 빠졌는지는 잠근 뒤 판단해야 동시에 진행되는 명부 제외를 놓치지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select co from ChildOrganization co where co.childId = :childId and co.organizationId = :organizationId")
    Optional<ChildOrganization> findByChildIdAndOrganizationIdForUpdate(
            @Param("childId") Long childId, @Param("organizationId") Long organizationId);

    /** 해제된 행은 관리번호가 비워져 있으므로 deletedAt 조건 없이도 DB 유니크 제약과 같은 기준이다. */
    boolean existsByOrganizationIdAndExternalId(Long organizationId, String externalId);

    List<ChildOrganization> findByOrganizationIdAndDeletedAtIsNull(Long organizationId);

    /**
     * 이 아이가 지금 다니는 기관들.
     *
     * <p>인사이트가 {@code consented_institutions} 를 채우는 데 쓴다 — {@code sharing_consent} 가
     * 생기기 전까지는 "등록된 기관 전부" 가 동의 목록을 대신한다 (AI/insight/schemas.py).
     * 연결이 끊긴 행을 빼지 않으면 더 이상 다니지 않는 기관의 기록까지 재료로 들어간다.
     */
    List<ChildOrganization> findByChildIdAndDeletedAtIsNull(Long childId);
}
