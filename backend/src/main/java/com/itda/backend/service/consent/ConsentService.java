package com.itda.backend.service.consent;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentAction;
import com.itda.backend.domain.consent.ConsentField;
import com.itda.backend.domain.consent.ConsentRevokeReason;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.consent.SharingConsent;
import com.itda.backend.domain.consent.SharingConsentStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.consent.ConsentPreviewResponse;
import com.itda.backend.dto.response.consent.ConsentResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.consent.ConsentErrorCode;
import com.itda.backend.exception.consent.ConsentException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.consent.SharingConsentRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.insight.InsightAccessRevoker;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final OrganizationRepository organizationRepository;
    private final SharingConsentRepository sharingConsentRepository;
    private final ConsentStateReader consentStateReader;
    private final InsightAccessRevoker insightAccessRevoker;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ConsentPreviewResponse preview(String userId, Long childId, Long institutionId) {
        Long parentId = userService.getParentIdOf(userId);
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw new ConsentException(ConsentErrorCode.CHILD_NOT_LINKED);
        }
        Child child = childRepository.findByIdAndDeletedAtIsNull(childId).orElseThrow(ConsentService::targetNotFound);
        if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, institutionId)
                || sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(
                        childId, institutionId, SharingConsentStatus.ACTIVE)) {
            throw targetNotFound();
        }
        Organization organization = organizationRepository.findById(institutionId)
                .orElseThrow(ConsentService::targetNotFound);

        return new ConsentPreviewResponse(
                new ConsentPreviewResponse.PreviewChild(
                        String.valueOf(child.getId()), child.getName(), child.getBirthdate().toString()),
                InstitutionResponse.from(organization),
                null,
                ConsentFieldCatalog.ALLOWED_FIELDS);
    }

    @Transactional
    public ConsentResponse update(
            String userId, Long childId, Long institutionId, ConsentAction action, List<ConsentField> allowedFields) {
        Long parentId = userService.getParentIdOf(userId);
        if (action == null || (action == ConsentAction.GRANT && !SharingConsent.isValidScope(allowedFields))) {
            throw new ConsentException(ConsentErrorCode.INVALID_CONSENT_REQUEST);
        }

        Optional<Child> lockedChild = childRepository.findByIdForUpdate(childId);
        Optional<ChildOrganization> enrollment =
                childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(childId, institutionId);
        boolean linked = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, parentId)
                .filter(guardian -> !guardian.isDeleted())
                .isPresent();
        if (!linked) {
            throw new ConsentException(ConsentErrorCode.CHILD_NOT_LINKED);
        }
        Child child = lockedChild.filter(found -> !found.isDeleted()).orElseThrow(ConsentService::targetNotFound);
        if (enrollment.filter(link -> !link.isDeleted()).isEmpty()) {
            throw targetNotFound();
        }

        List<SharingConsent> active = sharingConsentRepository.findByChildIdAndOrganizationIdAndStatus(
                childId, institutionId, SharingConsentStatus.ACTIVE);
        if (action == ConsentAction.GRANT) {
            boolean hadInsight = consentStateReader.insightStateOf(childId, institutionId) == ConsentState.GRANTED;
            active.forEach(consent -> consent.changeScope(allowedFields));
            if (active.stream().noneMatch(consent -> consent.getGuardianId().equals(parentId))) {
                sharingConsentRepository.save(SharingConsent.grant(childId, parentId, institutionId, allowedFields));
            }
            if (hadInsight && consentStateReader.insightStateOf(childId, institutionId) != ConsentState.GRANTED) {
                insightAccessRevoker.revokeForInstitution(
                        childId, institutionId, ConsentRevokeReason.GUARDIAN_REVOKED, now());
            }
        } else {
            LocalDateTime now = now();
            active.forEach(consent -> consent.revoke(parentId, ConsentRevokeReason.GUARDIAN_REVOKED, now));
            insightAccessRevoker.revokeForInstitution(
                    childId, institutionId, ConsentRevokeReason.GUARDIAN_REVOKED, now);
        }
        refreshStatus(child);

        ConsentState state = consentStateReader.stateOf(childId, institutionId);
        List<ConsentField> shared = state == ConsentState.GRANTED
                ? SharingConsent.normalize(allowedFields)
                : List.of();
        return new ConsentResponse(child.getStatus(), state, shared);
    }

    @Transactional
    public void revokeForUnlinkedGuardian(Child lockedChild, Long guardianId, Long actorId) {
        LocalDateTime now = now();
        List<SharingConsent> revoked = sharingConsentRepository.findByChildIdAndGuardianIdAndStatus(
                lockedChild.getId(), guardianId, SharingConsentStatus.ACTIVE);
        revoked.forEach(consent -> consent.revoke(actorId, ConsentRevokeReason.GUARDIAN_UNLINKED, now));

        insightAccessRevoker.revokeForGuardian(
                lockedChild.getId(), guardianId, ConsentRevokeReason.GUARDIAN_UNLINKED, now);
        revoked.stream()
                .map(SharingConsent::getOrganizationId)
                .distinct()
                .filter(organizationId ->
                        consentStateReader.insightStateOf(lockedChild.getId(), organizationId) != ConsentState.GRANTED)
                .forEach(organizationId -> insightAccessRevoker.revokeForInstitution(
                        lockedChild.getId(), organizationId, ConsentRevokeReason.GUARDIAN_UNLINKED, now));

        refreshStatus(lockedChild);
    }

    @Transactional
    public void revokeForRosterRemoval(Child lockedChild, Long organizationId, Long actorId) {
        LocalDateTime now = now();
        sharingConsentRepository.findByChildIdAndOrganizationIdAndStatus(
                        lockedChild.getId(), organizationId, SharingConsentStatus.ACTIVE)
                .forEach(consent -> consent.revoke(actorId, ConsentRevokeReason.ROSTER_REMOVED, now));
        insightAccessRevoker.revokeForInstitution(
                lockedChild.getId(), organizationId, ConsentRevokeReason.ROSTER_REMOVED, now);
        refreshStatus(lockedChild);
    }

    private void refreshStatus(Child child) {
        if (sharingConsentRepository.existsByChildIdAndStatus(child.getId(), SharingConsentStatus.ACTIVE)) {
            child.activate();
        } else if (sharingConsentRepository.existsByChildId(child.getId())) {
            child.suspend();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static ConsentException targetNotFound() {
        return new ConsentException(ConsentErrorCode.CONSENT_TARGET_NOT_FOUND);
    }
}
