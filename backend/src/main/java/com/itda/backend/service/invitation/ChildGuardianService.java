package com.itda.backend.service.invitation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.invitation.Invitation;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.invitation.ChildGuardianResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.invitation.InvitationErrorCode;
import com.itda.backend.exception.invitation.InvitationException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.invitation.InvitationRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.child.InstitutionLinkService;
import com.itda.backend.service.child.OrganizationChildGuard;
import com.itda.backend.service.consent.ConsentService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 아이와 보호자 연결의 조회·해제. 연결은 기관 구분 없는 전역 연결이라 해제하면 모든 기관에서 끊긴다.
 *
 * <p>기관은 자기가 발급한 코드로 생긴 연결만 끊을 수 있다. 다른 기관을 통해 들어온 보호자를 끊으면
 * 그 기관의 판단을 뒤집게 된다. 보호자는 자기 연결을 언제든 끊을 수 있다.
 *
 * <p>연결을 끊으면 그 보호자가 이 아이에 대해 남긴 동의도 함께 REVOKED 로 바꾼다. 끊긴 사람의 동의로 공유가
 * 계속되면 안 된다 (DB 스키마 §5.4). 동의를 바꾸므로 해제도 {@code child} 를 먼저 잠그고 {@code child_guardian} 을
 * 잠근다 — 동의 부여와 같은 순서라 서로 교착하지 않는다 (DB 스키마 §3.3).
 */
@Service
@RequiredArgsConstructor
public class ChildGuardianService {

    private final ChildGuardianRepository childGuardianRepository;
    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final OrganizationChildGuard organizationChildGuard;
    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentService consentService;
    private final InstitutionLinkService institutionLinkService;
    private final Clock clock;

    /** O-18 아이와 연결된 보호자 목록. 다른 기관을 통해 연결된 보호자도 보여 주되 해제할 수 있는지 표시한다. */
    @Transactional(readOnly = true)
    public List<ChildGuardianResponse> listGuardians(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        List<ChildGuardian> guardians = childGuardianRepository.findByChildIdAndDeletedAtIsNullOrderByLinkedAtAsc(childId);

        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(guardians.stream().map(ChildGuardian::getUserId).toList())) {
            names.put(user.getId(), user.getName());
        }
        Map<Long, Long> linkingOrganizations = new HashMap<>();
        for (Invitation invitation : invitationRepository.findAllById(
                guardians.stream().map(ChildGuardian::getInvitationId).toList())) {
            linkingOrganizations.put(invitation.getId(), invitation.getOrganizationId());
        }

        return guardians.stream()
                .map(guardian -> new ChildGuardianResponse(
                        String.valueOf(guardian.getUserId()),
                        names.get(guardian.getUserId()),
                        toOffset(guardian.getLinkedAt()),
                        organizationId.equals(linkingOrganizations.get(guardian.getInvitationId()))))
                .toList();
    }

    /**
     * O-19 기관이 보호자 연결을 끊는다. 자기 기관 코드로 생긴 연결이 아니면 403 이다. 끊긴 보호자의 동의와
     * 그 보호자가 낸 기관 연결 요청도 거둔다.
     *
     * <p>아동을 먼저 잠그고 그 뒤에 삭제·명부 소속을 본다. {@link OrganizationChildGuard} 를 쓰지 않는 이유다 —
     * 가드가 잠금 없이 아동을 읽어 두면 잠금 조회가 그 낡은 엔티티를 그대로 돌려주고, 동의 철회로 상태가 바뀔 때
     * 그사이 커밋된 삭제({@code deleted_at})까지 덮어써 되돌린다.
     */
    @Transactional
    public void unlinkByOrganization(String userId, Long childId, Long guardianUserId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Child child = childRepository.findByIdForUpdate(childId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_FOUND));
        if (!childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, organizationId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_FOUND);
        }
        // 잠금 순서상 연결 요청이 보호자 연결보다 앞이다 (DB 스키마 §3.3). 아래에서 실패하면 함께 롤백된다.
        institutionLinkService.cancelForUnlinkedGuardian(childId, guardianUserId);
        ChildGuardian guardian = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, guardianUserId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.GUARDIAN_NOT_FOUND));
        boolean linkedByThisOrganization = invitationRepository.findById(guardian.getInvitationId())
                .map(invitation -> organizationId.equals(invitation.getOrganizationId()))
                .orElse(false);
        if (!linkedByThisOrganization) {
            throw new InvitationException(InvitationErrorCode.GUARDIAN_LINKED_BY_OTHER_ORGANIZATION);
        }
        guardian.delete();
        consentService.revokeForUnlinkedGuardian(child, guardianUserId, userService.getUserIdOf(userId));
    }

    /**
     * G-05 보호자가 자기 연결을 끊는다. 연결되지 않은 아이면 api-spec §5 규칙대로 403 FORBIDDEN 이다. 본인의 동의와
     * 본인이 낸 기관 연결 요청도 거둔다.
     */
    @Transactional
    public void unlinkBySelf(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        Optional<Child> child = childRepository.findByIdForUpdate(childId);
        institutionLinkService.cancelForUnlinkedGuardian(childId, parentId);
        ChildGuardian guardian = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, parentId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.CHILD_NOT_LINKED));
        guardian.delete();
        child.ifPresent(found -> consentService.revokeForUnlinkedGuardian(found, parentId, parentId));
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }
}
