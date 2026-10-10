package com.itda.backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.dto.response.RegisteredChildResponse.RegisteredChild;
import com.itda.backend.exception.ChildErrorCode;
import com.itda.backend.exception.ChildException;
import com.itda.backend.repository.ChildOrganizationRepository;
import com.itda.backend.repository.ChildRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChildService {

    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<ChildRosterResponse> getRoster(String userId) {
        // O-10 명부 화면용 — pending_consent 아동도 포함해서 내려주고 화면에서 상태별로 구분한다.
        // AI 매칭 명부처럼 동의 완료 아동만 필요하면 findActiveByOrganizationId를 쓴다.
        Long organizationId = userService.getOrganizationIdOf(userId);
        Map<Long, String> externalIds = externalIdsByChildId(organizationId);
        return childRepository.findByOrganizationId(organizationId).stream()
                .map(child -> new ChildRosterResponse(
                        String.valueOf(child.getId()),
                        child.getName(),
                        child.getBirthdate().toString(),
                        externalIds.get(child.getId()),
                        child.getStatus()))
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
     * 새 아동은 PENDING_CONSENT 라서 AI 매칭 명부에는 보호자 동의 뒤에야 들어간다.
     *
     * <p>관리번호만은 같은 기관 안에서 겹칠 수 없다. 먼저 조회로 거르고, 두 요청이 동시에 같은 번호로 들어와
     * 조회를 함께 통과한 경우는 유니크 제약이 막는다 — 그 예외도 500 이 아니라 같은 409 로 바꾼다.
     * saveAndFlush 로 즉시 INSERT 해야 제약 위반이 여기서 드러난다. 어느 쪽이든 예외가 밖으로 나가므로
     * 먼저 저장한 아동도 함께 롤백된다.
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
        return new RegisteredChildResponse(new RegisteredChild(
                String.valueOf(child.getId()),
                child.getName(),
                child.getBirthdate().toString(),
                externalId,
                child.getStatus()));
    }

    private boolean isDuplicateExternalId(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && ChildOrganization.UK_ORGANIZATION_EXTERNAL_ID.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
