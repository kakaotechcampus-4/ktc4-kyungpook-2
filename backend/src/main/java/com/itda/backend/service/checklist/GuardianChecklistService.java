package com.itda.backend.service.checklist;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.checklist.Checklist;
import com.itda.backend.domain.checklist.ChecklistTarget;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.dto.response.checklist.GuardianChecklistResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.repository.checklist.ChecklistRepository;
import com.itda.backend.repository.checklist.ChecklistTargetRepository;
import com.itda.backend.repository.checklist.GuardianChecklistRow;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.service.user.UserService;
import com.itda.backend.service.child.GuardianChildGuard;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GuardianChecklistService {

    static final int CONFIRMED_LIMIT = 20;

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChecklistRepository checklistRepository;
    private final ChecklistTargetRepository checklistTargetRepository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<GuardianChecklistResponse> list(String userId, Long childId, boolean needsCheckOnly) {
        guardianChildGuard.requireLinkedChild(userService.getParentIdOf(userId), childId);
        if (needsCheckOnly) {
            return assemble(checklistTargetRepository.findUnconfirmedForChild(childId));
        }
        List<GuardianChecklistRow> rows = checklistTargetRepository.findVisibleForChild(childId);
        return assemble(Stream.concat(
                        rows.stream().filter(row -> !row.target().isConfirmed()),
                        rows.stream().filter(row -> row.target().isConfirmed()).limit(CONFIRMED_LIMIT))
                .toList());
    }

    @Transactional
    public GuardianChecklistResponse confirm(String userId, Long childId, Long checklistId) {
        Long parentId = userService.getParentIdOf(userId);
        guardianChildGuard.lockLinkedChildForShare(parentId, childId);
        Long targetId = checklistTargetRepository.findIdByChecklistIdAndChildId(checklistId, childId)
                .orElseThrow(ChecklistService::notFound);
        Checklist checklist = checklistRepository.findByIdForShare(checklistId).orElseThrow(ChecklistService::notFound);
        if (checklist.isCanceled() || !childOrganizationRepository
                .existsByChildIdAndOrganizationIdAndDeletedAtIsNull(childId, checklist.getOrganizationId())) {
            throw ChecklistService.notFound();
        }

        checklistTargetRepository.confirmIfUnconfirmed(targetId, parentId, LocalDateTime.now(clock));
        ChecklistTarget target = checklistTargetRepository.findById(targetId).orElseThrow(ChecklistService::notFound);
        return assemble(List.of(new GuardianChecklistRow(target, checklist))).get(0);
    }

    private List<GuardianChecklistResponse> assemble(List<GuardianChecklistRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, InstitutionResponse> institutions = organizationRepository
                .findAllById(rows.stream().map(row -> row.checklist().getOrganizationId()).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Organization::getId, InstitutionResponse::from));
        return rows.stream()
                .map(row -> {
                    ChecklistTarget target = row.target();
                    Checklist checklist = row.checklist();
                    return new GuardianChecklistResponse(
                            String.valueOf(checklist.getId()),
                            institutions.get(checklist.getOrganizationId()),
                            checklist.getItems(),
                            checklist.getCreatedAt(),
                            target.isConfirmed() ? "confirmed" : "needs_check",
                            target.getConfirmedAt());
                })
                .toList();
    }
}
