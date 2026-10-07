package com.itda.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.Child;
import com.itda.backend.domain.ChildOrganization;
import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.dto.request.RegisterChildRequest;
import com.itda.backend.dto.response.RegisteredChildResponse;
import com.itda.backend.dto.response.RegisteredChildResponse.RegisteredChild;
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
        return childRepository.findByOrganizationId(organizationId).stream()
                .map(child -> new ChildRosterResponse(
                        String.valueOf(child.getId()),
                        child.getName(),
                        child.getBirthdate().toString(),
                        child.getStatus()))
                .toList();
    }

    /**
     * O-11 아이 등록. 아동과 "우리 기관에 다닌다" 연결을 한 트랜잭션으로 만든다 — 연결 없이 아동만 남으면
     * 어느 명부에도 안 보이는 고아 행이 된다.
     *
     * <p>중복 확인을 하지 않는다. 같은 이름·생일이어도, 다른 기관에 이미 있는 아이여도 새 아동이다.
     * 기관끼리 서로의 아동을 조회할 수 없고, 같은 아이인지는 보호자 연결 단계에서 확인한다.
     * 새 아동은 PENDING_CONSENT 라서 AI 매칭 명부에는 보호자 동의 뒤에야 들어간다.
     */
    @Transactional
    public RegisteredChildResponse register(String userId, RegisterChildRequest request) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Child child = childRepository.save(Child.of(request.name(), request.birthDateValue()));
        childOrganizationRepository.save(ChildOrganization.of(child.getId(), organizationId));
        return new RegisteredChildResponse(new RegisteredChild(
                String.valueOf(child.getId()),
                child.getName(),
                child.getBirthdate().toString(),
                child.getStatus()));
    }
}
