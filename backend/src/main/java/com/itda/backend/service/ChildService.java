package com.itda.backend.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.dto.ChildRosterResponse;
import com.itda.backend.repository.ChildRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChildService {

    private final ChildRepository childRepository;
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
}
