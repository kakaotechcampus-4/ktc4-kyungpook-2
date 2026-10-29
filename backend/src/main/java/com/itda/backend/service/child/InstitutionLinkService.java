package com.itda.backend.service.child;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.child.ChildOrganizationRequest;
import com.itda.backend.domain.child.ChildOrganizationRequestStatus;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.child.ChildRosterResponse;
import com.itda.backend.dto.response.child.InstitutionLinkRequestResponse;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse.RequestedChild;
import com.itda.backend.dto.response.child.PendingLinkRequestResponse.Requester;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.exception.child.InstitutionLinkErrorCode;
import com.itda.backend.exception.child.InstitutionLinkException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildOrganizationRequestRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.invitation.InviteCodeGenerator;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 이미 있는 아이를 다른 기관 명부에 붙인다. 보호자가 기관 코드로 요청하고, 기관이 관리번호를 넣어 수락한다.
 *
 * <p>기관이 아이를 따로 등록하면 같은 아이가 둘이 된다. 이 길로 붙이면 아이는 하나로 남고 기관만 늘어서, 학교·센터의
 * 기록이 한 아이에게 모인다. 수락은 동의가 아니다 — 명부에 오른 뒤 보호자가 그 기관에 따로 동의해야 매칭이 시작된다.
 *
 * <p>잠금 순서는 child → child_organization → child_organization_request 다 (DB 스키마 §3.3). 모든 쓰기 경로가
 * 아이 행부터 잠그므로 같은 아이에 대한 요청·수락·거절·보호자 연결 해제가 줄을 선다. 요청 행은 아이를 잠근 뒤에
 * 잠그면서 처음 읽는다 — 미리 읽어 둔 엔티티가 있으면 그사이 취소된 행을 옛 상태 그대로 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
public class InstitutionLinkService {

    private final UserService userService;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChildOrganizationRequestRepository childOrganizationRequestRepository;
    private final ChildGuardianRepository childGuardianRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final ConsentStateReader consentStateReader;
    private final Clock clock;

    /** G-43 기관 코드로 기관을 찾는다. 조회만 하고 연결하지 않는다. 형식이 틀린 코드와 없는 코드를 나누지 않는다. */
    @Transactional(readOnly = true)
    public InstitutionResponse lookup(String userId, String rawCode) {
        userService.getParentIdOf(userId);
        return InstitutionResponse.from(organizationByCode(rawCode));
    }

    /**
     * G-44 보호자가 아이를 기관에 연결해 달라고 요청한다.
     *
     * <p>같은 보호자가 다시 요청하면 새로 만들지 않고 대기 중인 요청을 돌려준다. 다른 보호자의 대기 요청이 있어도
     * 자기 요청을 따로 만든다 — 먼저 요청한 보호자가 연결을 끊어도 이 보호자의 요청은 남아야 한다.
     */
    @Transactional
    public InstitutionLinkRequestResponse request(String userId, Long childId, String rawCode) {
        Long parentId = userService.getParentIdOf(userId);
        Organization organization = organizationByCode(rawCode);

        childRepository.findByIdForUpdate(childId)
                .filter(child -> !child.isDeleted())
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_LINKED));
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_LINKED);
        }
        if (childOrganizationRepository.existsByChildIdAndOrganizationIdAndDeletedAtIsNull(
                childId, organization.getId())) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_ALREADY_LINKED);
        }
        ChildOrganizationRequest request = childOrganizationRequestRepository
                .findFirstByChildIdAndOrganizationIdAndRequestedByAndStatus(
                        childId, organization.getId(), parentId, ChildOrganizationRequestStatus.PENDING)
                .orElseGet(() -> childOrganizationRequestRepository.save(
                        ChildOrganizationRequest.request(childId, organization.getId(), parentId)));
        return new InstitutionLinkRequestResponse(InstitutionResponse.from(organization),
                InstitutionLinkRequestResponse.PENDING, toOffset(request.getCreatedAt()));
    }

    /** 기관의 연결 요청 목록. 아이별로 묶고, 가장 먼저 요청한 아이부터. 삭제된 아이는 뺀다. */
    @Transactional(readOnly = true)
    public List<PendingLinkRequestResponse> pendingRequests(String userId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Map<Long, List<ChildOrganizationRequest>> byChild = childOrganizationRequestRepository
                .findByOrganizationIdAndStatusOrderByCreatedAtAscIdAsc(
                        organizationId, ChildOrganizationRequestStatus.PENDING)
                .stream()
                .collect(Collectors.groupingBy(ChildOrganizationRequest::getChildId, LinkedHashMap::new,
                        Collectors.toList()));
        if (byChild.isEmpty()) {
            return List.of();
        }
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(byChild.keySet()).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(byChild.values().stream()
                .flatMap(List::stream)
                .map(ChildOrganizationRequest::getRequestedBy)
                .distinct()
                .toList())) {
            names.put(user.getId(), user.getName());
        }
        return byChild.entrySet().stream()
                .filter(entry -> children.containsKey(entry.getKey()))
                .map(entry -> {
                    Child child = children.get(entry.getKey());
                    List<ChildOrganizationRequest> requests = entry.getValue();
                    return new PendingLinkRequestResponse(
                            new RequestedChild(String.valueOf(child.getId()), child.getName(),
                                    child.getBirthdate().toString()),
                            requests.stream().map(request -> new Requester(names.get(request.getRequestedBy())))
                                    .toList(),
                            toOffset(requests.get(0).getCreatedAt()));
                })
                .toList();
    }

    /**
     * 기관이 요청을 수락해 아이를 명부에 올린다. 아이 등록처럼 관리번호를 받는다. 이 아이에 대한 이 기관의 대기 요청은
     * 보호자가 여럿이어도 모두 함께 수락된다.
     *
     * <p>잠근 뒤 판단한다. 연결 해제가 먼저 끝나 요청이 거둬졌으면 남은 대기 요청이 없어 404 다.
     */
    @Transactional
    public ChildRosterResponse accept(String userId, Long childId, String externalId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Long actorId = userService.getUserIdOf(userId);
        Child child = lockLiveChild(childId);

        Optional<ChildOrganization> existing =
                childOrganizationRepository.findByChildIdAndOrganizationIdForUpdate(childId, organizationId);
        if (existing.filter(link -> !link.isDeleted()).isPresent()) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_ALREADY_LINKED);
        }
        List<ChildOrganizationRequest> pending = lockPending(childId, organizationId);

        if (childOrganizationRepository.existsByOrganizationIdAndExternalId(organizationId, externalId)) {
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }
        ChildOrganization link = existing.orElse(null);
        if (link == null) {
            link = ChildOrganization.of(childId, organizationId, externalId);
        } else {
            link.restore(externalId);
        }
        try {
            childOrganizationRepository.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            if (!ChildService.isDuplicateExternalId(e)) {
                throw e;
            }
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }

        LocalDateTime now = now();
        pending.forEach(request -> request.accept(actorId, now));
        return ChildService.toRosterResponse(child, externalId, consentStateReader.stateOf(childId, organizationId));
    }

    /** 기관이 요청을 거절한다. 이 아이에 대한 이 기관의 대기 요청이 모두 함께 거절된다. 보호자는 다시 요청할 수 있다. */
    @Transactional
    public void reject(String userId, Long childId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Long actorId = userService.getUserIdOf(userId);
        lockLiveChild(childId);
        List<ChildOrganizationRequest> pending = lockPending(childId, organizationId);
        LocalDateTime now = now();
        pending.forEach(request -> request.reject(actorId, now));
    }

    /**
     * 보호자 연결이 끊길 때 그 보호자가 이 아이에 대해 낸 대기 요청을 거둔다. 다른 보호자의 요청은 그대로 둔다.
     * 아이 행은 부르는 쪽이 이미 잠갔다.
     */
    @Transactional
    public void cancelForUnlinkedGuardian(Long childId, Long guardianId) {
        LocalDateTime now = now();
        childOrganizationRequestRepository.findPendingByChildIdAndRequestedByForUpdate(childId, guardianId)
                .forEach(request -> request.cancel(now));
    }

    private Organization organizationByCode(String rawCode) {
        String code = InviteCodeGenerator.normalize(rawCode);
        if (code == null) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_CODE_NOT_FOUND);
        }
        return organizationRepository.findByCode(code)
                .orElseThrow(() -> new InstitutionLinkException(InstitutionLinkErrorCode.INSTITUTION_CODE_NOT_FOUND));
    }

    /** 없거나 삭제된 아이는 요청이 없는 아이와 같은 404 다. */
    private Child lockLiveChild(Long childId) {
        return childRepository.findByIdForUpdate(childId)
                .filter(child -> !child.isDeleted())
                .orElseThrow(() -> new InstitutionLinkException(InstitutionLinkErrorCode.LINK_REQUEST_NOT_FOUND));
    }

    private List<ChildOrganizationRequest> lockPending(Long childId, Long organizationId) {
        List<ChildOrganizationRequest> pending =
                childOrganizationRequestRepository.findPendingByChildIdAndOrganizationIdForUpdate(childId, organizationId);
        if (pending.isEmpty()) {
            throw new InstitutionLinkException(InstitutionLinkErrorCode.LINK_REQUEST_NOT_FOUND);
        }
        return pending;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private OffsetDateTime toOffset(LocalDateTime time) {
        return time.atZone(clock.getZone()).toOffsetDateTime();
    }
}
