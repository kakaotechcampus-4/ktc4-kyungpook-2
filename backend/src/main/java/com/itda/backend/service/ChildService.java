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
        Long organizationId = userService.getOrganizationIdOf(userId);
        return childRepository.findActiveByOrganizationId(organizationId).stream()
                .map(child -> new ChildRosterResponse(
                        String.valueOf(child.getId()),
                        child.getName(),
                        child.getBirthdate().toString(),
                        child.getStatus()))
                .toList();
    }
}
