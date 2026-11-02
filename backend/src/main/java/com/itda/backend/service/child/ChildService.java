package com.itda.backend.service.child;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.request.child.RegisterChildRequest;
import com.itda.backend.dto.response.child.ChildRosterResponse;
import com.itda.backend.dto.response.child.RegisteredChildResponse;
import com.itda.backend.dto.response.child.RegisteredChildResponse.RegisteredChild;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.global.exception.ConstraintViolations;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.consent.ConsentStateReader;
import com.itda.backend.service.invitation.InvitationService;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChildService {

    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final UserService userService;
    private final InvitationService invitationService;
    private final ConsentStateReader consentStateReader;

    @Transactional(readOnly = true)
    public List<ChildRosterResponse> getRoster(String userId) {
        // O-10 명부 화면용 — pending_consent 아동도 포함해서 내려주고 화면에서 상태별로 구분한다.
        // AI 매칭 명부처럼 이 기관에 동의한 아동만 필요하면 findConsentedByOrganizationId를 쓴다.
        Long organizationId = userService.getOrganizationIdOf(userId);
        Map<Long, String> externalIds = externalIdsByChildId(organizationId);
        List<Child> children = childRepository.findByOrganizationId(organizationId);
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(
                children.stream().map(Child::getId).toList());
        return children.stream()
                .map(child -> toRosterResponse(child, externalIds.get(child.getId()),
                        consents.getOrDefault(child.getId(), Map.of())
                                .getOrDefault(organizationId, ConsentState.NOT_GRANTED)))
                .toList();
    }

    // 관리번호가 없는 아이는 값이 null 이라 Collectors.toMap 을 쓰면 NPE 가 난다. HashMap 에 직접 담는다.
    private Map<Long, String> externalIdsByChildId(Long organizationId) {
        Map<Long, String> externalIds = new HashMap<>();
        for (ChildOrganization link : childOrganizationRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId)) {
            externalIds.put(link.getChildId(), link.getExternalId());
        }
        return externalIds;
    }

    /**
     * O-11 아이 등록. 아동과 "우리 기관에 다닌다" 연결을 한 트랜잭션으로 만든다 — 연결 없이 아동만 남으면
     * 어느 명부에도 안 보이는 고아 행이 된다.
     *
     * <p>중복 확인을 하지 않는다. 같은 이름·생일이어도, 다른 기관에 이미 있는 아이여도 새 아동이다.
     * 기관끼리 서로의 아동을 조회할 수 없고, 같은 아이인지는 보호자 연결 단계에서 확인한다.
     * 새 아동은 PENDING_CONSENT 라서 AI 매칭 명부에는 보호자가 이 기관에 동의한 뒤에야 들어간다.
     *
     * <p>관리번호만은 같은 기관 안에서 겹칠 수 없다. 먼저 조회로 거르고, 두 요청이 동시에 같은 번호로 들어와
     * 조회를 함께 통과한 경우는 유니크 제약이 막는다 — 그 예외도 500 이 아니라 같은 409 로 바꾼다.
     * saveAndFlush 로 즉시 INSERT 해야 제약 위반이 여기서 드러난다. 어느 쪽이든 예외가 밖으로 나가므로
     * 먼저 저장한 아동도 함께 롤백된다.
     *
     * <p>보호자에게 보낼 초대 코드도 같은 트랜잭션에서 발급한다. 코드를 못 만들면 아이 등록도 남지 않는다.
     */
    @Transactional
    public RegisteredChildResponse register(String userId, RegisterChildRequest request) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        String externalId = request.externalId();
        if (externalId != null && childOrganizationRepository.existsByOrganizationIdAndExternalId(organizationId, externalId)) {
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }
        Child child = childRepository.save(Child.of(request.name(), request.birthDateValue()));
        try {
            childOrganizationRepository.saveAndFlush(ChildOrganization.of(child.getId(), organizationId, externalId));
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateExternalId(e)) {
                throw e;
            }
            throw new ChildException(ChildErrorCode.DUPLICATE_EXTERNAL_ID);
        }
        return new RegisteredChildResponse(
                new RegisteredChild(
                        String.valueOf(child.getId()),
                        child.getName(),
                        child.getBirthdate().toString(),
                        externalId,
                        child.getStatus()),
                invitationService.issue(organizationId, child.getId()));
    }

    /** 명부 한 줄. {@code consent} 는 이 기관에 대한 동의라 아이 전체 요약인 {@code status} 와 다를 수 있다. */
    public static ChildRosterResponse toRosterResponse(Child child, String externalId, ConsentState consent) {
        return new ChildRosterResponse(
                String.valueOf(child.getId()),
                child.getName(),
                child.getBirthdate().toString(),
                externalId,
                child.getStatus(),
                consent);
    }

    /** 관리번호 유니크 제약에 걸렸는지. 아이 등록과 연결 요청 수락이 같은 409 로 바꾼다. */
    public static boolean isDuplicateExternalId(DataIntegrityViolationException exception) {
        return ConstraintViolations.isViolationOf(exception, ChildOrganization.UK_ORGANIZATION_EXTERNAL_ID);
    }
}
