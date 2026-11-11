package com.itda.backend.repository.consent;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;

public interface SharingConsentRepository extends JpaRepository<SharingConsent, Long> {

    List<SharingConsent> findByChildIdAndOrganizationIdAndStatus(
            Long childId, Long organizationId, SharingConsentStatus status);

    List<SharingConsent> findByChildIdAndGuardianIdAndStatus(Long childId, Long guardianId, SharingConsentStatus status);

    List<SharingConsent> findByChildIdIn(Collection<Long> childIds);

    boolean existsByChildIdAndOrganizationIdAndStatus(Long childId, Long organizationId, SharingConsentStatus status);

    boolean existsByChildIdAndOrganizationId(Long childId, Long organizationId);

    List<SharingConsent> findByChildIdAndOrganizationId(Long childId, Long organizationId);

    boolean existsByChildIdAndStatus(Long childId, SharingConsentStatus status);

    boolean existsByChildId(Long childId);
}
