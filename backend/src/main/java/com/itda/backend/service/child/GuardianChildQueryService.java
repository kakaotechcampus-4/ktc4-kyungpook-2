package com.itda.backend.service.child;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.domain.invitation.ChildGuardian;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.child.GuardianChildResponse;
import com.itda.backend.dto.response.child.GuardianChildResponse.ChildInstitution;
import com.itda.backend.dto.response.child.GuardianHomeResponse;
import com.itda.backend.dto.response.consent.PendingConsentRequestResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 보호자 화면이 받는 아이 정보 (api-spec G-10·G-11). 아이마다 지금 다니는 기관과 기관별 동의 상태를 붙인다.
 *
 * <p>동의 전·동의를 모두 끈 아이도 넣는다. 설정 화면에서 기관을 다시 켜거나 연결을 끊으려면 목록에 보여야 한다.
 *
 * <p>아이가 몇 명이든 조회 횟수가 같다. 연결 → 아이 → 명부 → 기관 → 동의 순서로 한 번씩 {@code IN} 조회한다.
 *
 * <p>대기 중 기관 권한 요청(G-40)도 여기서 만든다. 홈의 기관 목록에서 아직 아무도 동의하지 않은
 * ({@code not_granted}) 기관만 고른 것과 같아서다. 동의했다가 꺼진({@code revoked}) 기관은 보호자가 직접 끈 것이라
 * 다시 알리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class GuardianChildQueryService {

    private final UserService userService;
    private final ChildGuardianRepository childGuardianRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final OrganizationRepository organizationRepository;
    private final ConsentStateReader consentStateReader;

    /** 이 보호자와 연결된 아이 전부. 연결된 순서다. */
    @Transactional(readOnly = true)
    public List<GuardianChildResponse> children(String userId) {
        Long parentId = userService.getParentIdOf(userId);
        List<Long> childIds = childGuardianRepository.findByUserIdAndDeletedAtIsNullOrderByLinkedAtAscIdAsc(parentId)
                .stream()
                .map(ChildGuardian::getChildId)
                .distinct()
                .toList();
        return assemble(childIds);
    }

    /**
     * 아이 하나의 홈. 연결되지 않았거나 삭제된 아이면 403 이다 — 이유를 나누지 않는다.
     * 최근 공유 활동은 아직 만드는 곳이 없어 언제나 빈 배열이다.
     */
    @Transactional(readOnly = true)
    public GuardianHomeResponse home(String userId, Long childId) {
        return new GuardianHomeResponse(linkedChild(userId, childId), List.of());
    }

    /**
     * G-40. 이 아이가 명부에 올라 있는데 아직 아무도 동의하지 않은 기관. 기관 id 순이다.
     * 연결되지 않았거나 삭제된 아이면 홈과 같은 403 이다.
     */
    @Transactional(readOnly = true)
    public List<PendingConsentRequestResponse> pendingRequests(String userId, Long childId) {
        return linkedChild(userId, childId).institutions().stream()
                .filter(institution -> institution.consent() == ConsentState.NOT_GRANTED)
                .map(institution -> new PendingConsentRequestResponse(institution.institution()))
                .toList();
    }

    /** 이 보호자와 연결된 살아 있는 아이 하나. 아니면 이유를 나누지 않고 403 이다. */
    private GuardianChildResponse linkedChild(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_LINKED);
        }
        return assemble(List.of(childId)).stream()
                .findFirst()
                .orElseThrow(() -> new ChildException(ChildErrorCode.CHILD_NOT_LINKED));
    }

    /** {@code childIds} 순서대로. 삭제된 아이는 빠진다. */
    private List<GuardianChildResponse> assemble(List<Long> childIds) {
        if (childIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Child> children = childRepository.findByIdInAndDeletedAtIsNull(childIds).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        Map<Long, List<Long>> enrolled = childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(childIds)
                .stream()
                .collect(Collectors.groupingBy(ChildOrganization::getChildId,
                        Collectors.mapping(ChildOrganization::getOrganizationId, Collectors.toList())));
        Map<Long, Organization> organizations = organizationsOf(enrolled);
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(childIds);

        return childIds.stream()
                .map(children::get)
                .filter(Objects::nonNull)
                .map(child -> toResponse(child,
                        enrolled.getOrDefault(child.getId(), List.of()),
                        organizations,
                        consents.getOrDefault(child.getId(), Map.of())))
                .toList();
    }

    private Map<Long, Organization> organizationsOf(Map<Long, List<Long>> enrolled) {
        List<Long> ids = enrolled.values().stream().flatMap(List::stream).distinct().toList();
        Map<Long, Organization> organizations = new HashMap<>();
        if (!ids.isEmpty()) {
            organizationRepository.findAllById(ids).forEach(org -> organizations.put(org.getId(), org));
        }
        return organizations;
    }

    private static GuardianChildResponse toResponse(Child child, List<Long> organizationIds,
            Map<Long, Organization> organizations, Map<Long, ConsentState> consentOfChild) {
        List<ChildInstitution> institutions = organizationIds.stream()
                .distinct()
                // 기관 행이 사라진 명부 항목은 뺀다. 이름을 모르는 기관을 화면에 올리지 않는다 (Gate 2 공유 대상과 같다).
                .map(organizations::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Organization::getId))
                .map(org -> new ChildInstitution(InstitutionResponse.from(org),
                        consentOfChild.getOrDefault(org.getId(), ConsentState.NOT_GRANTED)))
                .toList();
        return new GuardianChildResponse(
                String.valueOf(child.getId()),
                child.getName(),
                child.getBirthdate().toString(),
                child.getStatus(),
                institutions);
    }
}
