package com.itda.backend.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildGuardian;
import com.itda.backend.domain.Invitation;
import com.itda.backend.dto.response.AcceptedInvitationResponse;
import com.itda.backend.dto.response.IssuedInvitationResponse;
import com.itda.backend.dto.response.LiveInvitationResponse;
import com.itda.backend.exception.InvitationErrorCode;
import com.itda.backend.exception.InvitationException;
import com.itda.backend.repository.ChildGuardianRepository;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;
import com.itda.backend.repository.InvitationRepository;
import com.itda.backend.repository.InvitationTarget;
import com.itda.backend.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;

/**
 * 보호자 초대 코드의 발급·조회·취소·수락. 코드는 본인 확인이 아니라 "이 아이와 연결해도 된다"는 일회용 권한이다.
 *
 * <p>시각은 주입받은 {@link Clock} 으로 구한다. 잠금이 필요한 메서드는 잠금을 얻은 뒤에 시각을 구한다 —
 * 잠금을 기다리는 동안 만료된 코드를 통과시키지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    static final Duration TIME_TO_LIVE = Duration.ofMinutes(10);
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final InvitationRepository invitationRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationChildGuard organizationChildGuard;
    private final UserService userService;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final Clock clock;

    /**
     * 권한 확인이 끝난 아이에게 코드를 발급한다. O-11 아이 등록이 같은 트랜잭션에서 부른다.
     *
     * <p>이미 있는 코드와 겹치지 않을 때까지 조회로 다시 뽑는다. 조회만 하므로 트랜잭션이 깨지지 않는다.
     * 그 사이 다른 요청이 같은 코드를 먼저 저장하면 유니크 제약이 막는데, 그때는 트랜잭션이 이미 깨져
     * 여기서 다시 저장할 수 없다. 요청 전체를 503 으로 끝내고(O-11 이면 아이 등록도 롤백) 클라이언트가 다시 보낸다.
     */
    @Transactional
    public IssuedInvitationResponse issue(Long organizationId, Long childId) {
        Invitation invitation = Invitation.issue(childId, organizationId, unusedCode(), now().plus(TIME_TO_LIVE));
        try {
            invitationRepository.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            if (!isViolationOf(e, Invitation.UK_INVITATION_CODE)) {
                throw e;
            }
            throw new InvitationException(InvitationErrorCode.INVITE_CODE_ISSUE_FAILED);
        }
        return new IssuedInvitationResponse(
                String.valueOf(invitation.getId()), invitation.getCode(), toOffset(invitation.getExpiresAt()));
    }

    /** O-15 추가 발급. 엄마·아빠에게 따로 보내도록 한 아이에 쓸 수 있는 코드가 여러 개 있어도 된다. */
    @Transactional
    public IssuedInvitationResponse issueFor(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        return issue(organizationId, childId);
    }

    /** O-16 아직 쓸 수 있는 코드 목록. 수락·취소·만료된 코드는 빠진다. */
    @Transactional(readOnly = true)
    public List<LiveInvitationResponse> listLive(String userId, Long childId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        return invitationRepository.findLive(childId, organizationId, now()).stream()
                .map(invitation -> new LiveInvitationResponse(
                        String.valueOf(invitation.getId()),
                        invitation.getCode(),
                        toOffset(invitation.getCreatedAt()),
                        toOffset(invitation.getExpiresAt())))
                .toList();
    }

    /**
     * O-17 코드 취소. 행을 잠근 뒤의 시각으로 쓸 수 있는지 본다 — 수락과 겹치면 먼저 잠근 쪽이 이긴다.
     * 이미 쓸 수 없는 코드는 목록에 없는 코드라 404 다.
     */
    @Transactional
    public void cancel(String userId, Long childId, Long invitationId) {
        Long organizationId = organizationChildGuard.requireRosterChild(userId, childId);
        Invitation invitation = invitationRepository
                .findByIdAndChildIdAndOrganizationIdForUpdate(invitationId, childId, organizationId)
                .orElseThrow(() -> new InvitationException(InvitationErrorCode.INVITATION_NOT_FOUND));
        LocalDateTime now = now();
        if (!invitation.isUsable(now)) {
            throw new InvitationException(InvitationErrorCode.INVITATION_NOT_FOUND);
        }
        invitation.cancel(now);
    }

    /**
     * G-04 보호자가 코드를 써서 아이와 연결된다. 코드 소모와 연결 생성은 한 트랜잭션이라, 어디서 실패해도 둘 다 남지 않는다.
     *
     * <p>잠금 순서는 child → child_organization → invitation → child_guardian 이다 (DB 스키마 §3.3).
     * 아이 삭제·명부 제외가 동시에 진행돼도 그 행을 잠그고 기다린 뒤 커밋된 상태를 보고 판단한다.
     *
     * <p>수락은 아동 상태를 바꾸지 않는다. 연결은 동의가 아니다 — 동의는 별도 기능이 맡는다.
     */
    @Transactional
    public AcceptedInvitationResponse accept(String userId, String rawCode) {
        Long parentId = userService.getParentIdOf(userId);
        String code = InviteCodeGenerator.normalize(rawCode);
        if (code == null) {
            throw invalidCode();
        }
        InvitationTarget target = invitationRepository.findTargetByCode(code).orElseThrow(InvitationService::invalidCode);

        Child child = childRepository.findByIdForUpdate(target.childId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(InvitationService::invalidCode);
        childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(target.childId(), target.organizationId())
                .filter(link -> !link.isDeleted())
                .orElseThrow(InvitationService::invalidCode);
        Invitation invitation = invitationRepository.findByIdForUpdate(target.id())
                .orElseThrow(InvitationService::invalidCode);
        if (!invitation.isUsable(now())) {
            throw invalidCode();
        }

        linkGuardian(child.getId(), parentId, invitation.getId());

        // 연결 행 잠금을 기다리는 동안 만료됐을 수 있다. 앞에서 구한 시각을 다시 쓰지 않는다.
        LocalDateTime now = now();
        if (!invitation.isUsable(now)) {
            throw invalidCode();
        }
        invitation.accept(parentId, now);

        String organizationName = organizationRepository.findById(target.organizationId())
                .map(organization -> organization.getName())
                .orElse(null);
        return new AcceptedInvitationResponse(
                String.valueOf(child.getId()), child.getName(), organizationName, child.getStatus());
    }

    /**
     * 연결 행을 잠근 뒤 살아 있는지 다시 본다. 해제된 행은 새 초대로 되살리고, 없으면 새로 만든다 (DB 스키마 §3.3).
     * 이미 연결돼 있으면 409 — 예외로 트랜잭션이 롤백되므로 코드는 쓰이지 않은 채 남는다.
     *
     * <p>행이 없을 때는 잠글 것이 없어서 동시에 두 INSERT 가 들어올 수 있다. 그건 유니크 제약이 막고, 같은 409 로 바꾼다.
     */
    private void linkGuardian(Long childId, Long parentId, Long invitationId) {
        Optional<ChildGuardian> existing = childGuardianRepository.findByChildIdAndUserIdForUpdate(childId, parentId);
        if (existing.isPresent()) {
            ChildGuardian guardian = existing.get();
            if (!guardian.isDeleted()) {
                throw new InvitationException(InvitationErrorCode.LINK_ALREADY_EXISTS);
            }
            guardian.relink(invitationId);
            return;
        }
        try {
            childGuardianRepository.saveAndFlush(ChildGuardian.link(childId, parentId, invitationId));
        } catch (DataIntegrityViolationException e) {
            if (!isViolationOf(e, ChildGuardian.UK_CHILD_GUARDIAN)) {
                throw e;
            }
            throw new InvitationException(InvitationErrorCode.LINK_ALREADY_EXISTS);
        }
    }

    private String unusedCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (!invitationRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new InvitationException(InvitationErrorCode.INVITE_CODE_ISSUE_FAILED);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }

    private static InvitationException invalidCode() {
        return new InvitationException(InvitationErrorCode.INVALID_INVITE_CODE);
    }

    private static boolean isViolationOf(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
