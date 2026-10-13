package com.itda.backend.repository.consent;

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

    boolean existsByChildIdAndOrganizationIdAndStatus(Long childId, Long organizationId, SharingConsentStatus status);

    boolean existsByChildIdAndOrganizationId(Long childId, Long organizationId);

    boolean existsByChildIdAndStatus(Long childId, SharingConsentStatus status);

    boolean existsByChildId(Long childId);
}
