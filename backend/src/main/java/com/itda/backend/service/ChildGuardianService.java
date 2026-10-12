package com.itda.backend.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.ChildGuardian;
import com.itda.backend.domain.Invitation;
import com.itda.backend.domain.User;
import com.itda.backend.dto.response.ChildGuardianResponse;
import com.itda.backend.exception.InvitationErrorCode;
import com.itda.backend.exception.InvitationException;
import com.itda.backend.repository.ChildGuardianRepository;
import com.itda.backend.repository.InvitationRepository;
import com.itda.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 아이와 보호자 연결의 조회·해제. 연결은 기관 구분 없는 전역 연결이라 해제하면 모든 기관에서 끊긴다.
 *
 * <p>기관은 자기가 발급한 코드로 생긴 연결만 끊을 수 있다. 다른 기관을 통해 들어온 보호자를 끊으면
 * 그 기관의 판단을 뒤집게 된다. 보호자는 자기 연결을 언제든 끊을 수 있다.
 *
 * <p>TODO 동의(sharing_consent)를 구현할 때: 연결을 끊으면 그 보호자가 이 아이에 대해 남긴 동의도 함께
 * REVOKED 로 바꾼다. 끊긴 사람의 동의로 공유가 계속되면 안 된다 (DB 스키마 §5.4).
 */
@Service
@RequiredArgsConstructor
public class ChildGuardianService {

    private final ChildGuardianRepository childGuardianRepository;
    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final OrganizationChildGuard organizationChildGuard;
    private final UserService userService;
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

    /** O-19 기관이 보호자 연결을 끊는다. 자기 기관 코드로 생긴 연결이 아니면 403 이다. */
    @Transactional
    public void unlinkByOrganization(String userId, Long childId, Long guardianUserId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
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
    }

    /** G-05 보호자가 자기 연결을 끊는다. 연결되지 않은 아이면 api-spec §5 규칙대로 403 FORBIDDEN 이다. */
    @Transactional
    public void unlinkBySelf(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        ChildGuardian guardian = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, parentId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.CHILD_NOT_LINKED));
        guardian.delete();
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }
}
