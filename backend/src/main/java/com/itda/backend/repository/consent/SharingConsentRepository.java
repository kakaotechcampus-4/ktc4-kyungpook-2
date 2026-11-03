package com.itda.backend.repository.consent;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;

/**
 * 동의 행은 따로 잠그지 않는다. 같은 아이의 동의를 바꾸는 쪽은 모두 {@code child} 행을 먼저 잠근다 (DB 스키마 §3.3).
 */
public interface SharingConsentRepository extends JpaRepository<SharingConsent, Long> {

    List<SharingConsent> findByChildIdAndOrganizationIdAndStatus(
            Long childId, Long organizationId, SharingConsentStatus status);

    List<SharingConsent> findByChildIdAndGuardianIdAndStatus(Long childId, Long guardianId, SharingConsentStatus status);

    List<SharingConsent> findByChildIdAndStatus(Long childId, SharingConsentStatus status);

    /**
     * 여러 아이의 동의 행 전부. {@code ConsentStateReader} 가 기관마다 GRANTED·REVOKED·NOT_GRANTED 를 가르는 데 쓴다 —
     * ACTIVE 만 받으면 "철회됨" 과 "동의한 적 없음" 을 구분하지 못한다.
     */
    List<SharingConsent> findByChildIdIn(Collection<Long> childIds);

    boolean existsByChildIdAndOrganizationIdAndStatus(Long childId, Long organizationId, SharingConsentStatus status);

    boolean existsByChildIdAndOrganizationId(Long childId, Long organizationId);

    /** 그 아이·기관의 동의 행 전부 (꺼진 것 포함). 한 기관의 인사이트 동의 상태를 볼 때 쓴다. */
    List<SharingConsent> findByChildIdAndOrganizationId(Long childId, Long organizationId);

    boolean existsByChildIdAndStatus(Long childId, SharingConsentStatus status);

    boolean existsByChildId(Long childId);
}
