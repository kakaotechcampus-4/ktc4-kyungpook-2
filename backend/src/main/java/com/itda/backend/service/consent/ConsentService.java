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
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 보호자의 기관별 공유 동의 (DB 스키마 §5.5). 매칭 대상인지는 이 동의로 판단하고, {@code child.status} 는
 * 동의를 요약한 화면용 값으로 함께 맞춘다.
 *
 * <p>규칙
 * <ul>
 *   <li>보호자 한 명만 동의해도 그 기관 공유가 켜진다.</li>
 *   <li>공유 항목은 기관 단위로 하나다. 마지막으로 동의한 보호자의 항목으로 그 기관의 ACTIVE 행을 모두 맞춘다.</li>
 *   <li>보호자 한 명이 철회하면 그 기관의 <b>모든 보호자</b> 동의가 꺼진다. 끄는 쪽을 우선한다(Consent First).</li>
 *   <li>보호자 연결이 끊기면 끊긴 그 사람의 동의만 꺼진다. 남은 보호자의 판단은 그대로 둔다.</li>
 * </ul>
 *
 * <p>동의를 바꾸는 작업은 모두 {@code child} 행을 먼저 잠근다. 동의 행에는 UNIQUE 를 걸 수 없어서(이력 테이블)
 * 이 잠금이 중복 ACTIVE 행과 상태 계산 경합을 막는다. 잠금 순서는
 * child → child_organization → invitation → child_guardian → sharing_consent 다 (DB 스키마 §3.3).
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final OrganizationRepository organizationRepository;
    private final SharingConsentRepository sharingConsentRepository;
    private final Clock clock;

    /**
     * G-02 동의 화면에 그릴 아이·기관·공유 범위. 연결되지 않은 아이면 403, 동의할 대상이 아니면 404 다.
     * 이미 그 기관에 살아 있는 동의가 있으면 "동의할 것이 없다"로 보고 404 다 — 다른 보호자가 동의했어도 같다.
     */
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

        ConsentFieldCatalog.Labels labels = ConsentFieldCatalog.labelsOf(organization.getType());
        return new ConsentPreviewResponse(
                new ConsentPreviewResponse.PreviewChild(
                        String.valueOf(child.getId()), child.getName(), child.getBirthdate().toString()),
                InstitutionResponse.from(organization),
                null,
                labels.shared(),
                labels.notShared(),
                ConsentFieldCatalog.ALLOWED_FIELDS);
    }

    /**
     * G-41 동의 부여·철회. 철회할 동의가 없으면 아무것도 바꾸지 않고 현재 상태를 돌려준다 — PUT 이라 같은 요청을
     * 여러 번 보내도 결과가 같아야 한다.
     *
     * <p>잠근 뒤에 판단한다. 연결 해제·명부 제외·다른 보호자의 동의가 동시에 진행돼도 커밋된 상태를 보고 정한다.
     */
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
            active.forEach(consent -> consent.changeScope(allowedFields));
            if (active.stream().noneMatch(consent -> consent.getGuardianId().equals(parentId))) {
                sharingConsentRepository.save(SharingConsent.grant(childId, parentId, institutionId, allowedFields));
            }
        } else {
            LocalDateTime now = now();
            active.forEach(consent -> consent.revoke(parentId, ConsentRevokeReason.GUARDIAN_REVOKED, now));
        }
        refreshStatus(child);

        ConsentState state = stateOf(childId, institutionId);
        List<ConsentField> shared = state == ConsentState.GRANTED
                ? SharingConsent.normalize(allowedFields)
                : List.of();
        return new ConsentResponse(child.getStatus(), state, shared);
    }

    /**
     * 연결이 끊긴 보호자가 이 아이에 남긴 동의를 모든 기관에서 끈다 (DB 스키마 §5.4). 다른 보호자의 동의는 그대로다.
     *
     * <p>호출하는 쪽이 이미 {@code child} 행을 잠갔다는 전제다 — 연결 해제가 child → child_guardian 순서로 잠근 뒤
     * 같은 트랜잭션에서 부른다. 그 트랜잭션은 잠그기 전에 아동을 읽어 두면 안 된다. 낡은 엔티티의 상태를 바꾸면
     * 그사이 커밋된 삭제까지 덮어쓴다.
     *
     * @param actorId 연결을 끊은 사용자. 보호자 본인이거나 기관 담당자다
     */
    @Transactional
    public void revokeForUnlinkedGuardian(Child lockedChild, Long guardianId, Long actorId) {
        LocalDateTime now = now();
        sharingConsentRepository.findByChildIdAndGuardianIdAndStatus(
                        lockedChild.getId(), guardianId, SharingConsentStatus.ACTIVE)
                .forEach(consent -> consent.revoke(actorId, ConsentRevokeReason.GUARDIAN_UNLINKED, now));
        refreshStatus(lockedChild);
    }

    /**
     * 기관별 동의를 아이 전체 요약으로 옮긴다. 살아 있는 동의가 있으면 ACTIVE, 동의 행은 있지만 모두 꺼졌으면 SUSPENDED.
     * 동의 행이 하나도 없으면 PENDING_CONSENT 그대로다 — 동의 행은 지우지 않으므로 한 번 생기면 다시 없어지지 않는다.
     *
     * <p>조회 전에 Hibernate 가 바뀐 동의 행을 먼저 내보낸다(같은 테이블 JPQL 이라 자동 flush).
     */
    private void refreshStatus(Child child) {
        if (sharingConsentRepository.existsByChildIdAndStatus(child.getId(), SharingConsentStatus.ACTIVE)) {
            child.activate();
        } else if (sharingConsentRepository.existsByChildId(child.getId())) {
            child.suspend();
        }
    }

    private ConsentState stateOf(Long childId, Long organizationId) {
        if (sharingConsentRepository.existsByChildIdAndOrganizationIdAndStatus(
                childId, organizationId, SharingConsentStatus.ACTIVE)) {
            return ConsentState.GRANTED;
        }
        return sharingConsentRepository.existsByChildIdAndOrganizationId(childId, organizationId)
                ? ConsentState.REVOKED
                : ConsentState.NOT_GRANTED;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static ConsentException targetNotFound() {
        return new ConsentException(ConsentErrorCode.CONSENT_TARGET_NOT_FOUND);
    }
}
