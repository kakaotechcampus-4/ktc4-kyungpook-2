package com.itda.backend.service.checklist;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.checklist.Checklist;
import com.itda.backend.domain.checklist.ChecklistTarget;
import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.dto.response.checklist.SentChecklistPageResponse;
import com.itda.backend.dto.response.checklist.SentChecklistResponse;
import com.itda.backend.exception.checklist.ChecklistErrorCode;
import com.itda.backend.exception.checklist.ChecklistException;
import com.itda.backend.repository.checklist.ChecklistRepository;
import com.itda.backend.repository.checklist.ChecklistTargetRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChecklistService {

    static final int PAGE_SIZE = 50;

    private final UserService userService;
    private final ChecklistRepository checklistRepository;
    private final ChecklistTargetRepository checklistTargetRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final Clock clock;

    @Transactional
    public SentChecklistResponse send(String userId, List<String> items, List<Long> childIds) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        Long staffId = userService.getUserIdOf(userId);
        Map<Long, Child> children = childrenById(childIds);
        Set<Long> onRoster = onRoster(organizationId, children);
        if (!onRoster.containsAll(childIds)) {
            throw new ChecklistException(ChecklistErrorCode.INVALID_CHECKLIST_TARGET);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Checklist checklist = checklistRepository.save(Checklist.send(organizationId, items, staffId, now));
        List<ChecklistTarget> targets = checklistTargetRepository.saveAll(childIds.stream()
                .map(childId -> ChecklistTarget.of(checklist.getId(), childId, now))
                .toList());
        return toResponse(checklist, targets, children, onRoster);
    }

    @Transactional(readOnly = true)
    public SentChecklistPageResponse list(String userId, Long before) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        PageRequest page = PageRequest.of(0, PAGE_SIZE + 1);
        List<Checklist> rows = before == null
                ? checklistRepository.findActivePage(organizationId, page)
                : checklistRepository.findActivePageBefore(organizationId,
                        checklistRepository.findCreatedAtOf(before, organizationId)
                                .orElseThrow(() -> new ChecklistException(ChecklistErrorCode.INVALID_CHECKLIST_CURSOR)),
                        before, page);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<Checklist> checklists = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, List<ChecklistTarget>> targetsByChecklist = checklistTargetRepository
                .findByChecklistIdIn(checklists.stream().map(Checklist::getId).toList()).stream()
                .collect(Collectors.groupingBy(ChecklistTarget::getChecklistId));
        List<ChecklistTarget> allTargets = targetsByChecklist.values().stream().flatMap(List::stream).toList();
        Map<Long, Child> children = childrenById(allTargets.stream().map(ChecklistTarget::getChildId).toList());
        Set<Long> onRoster = onRoster(organizationId, children);

        List<SentChecklistResponse> responses = checklists.stream()
                .map(checklist -> toResponse(checklist, targetsByChecklist.getOrDefault(checklist.getId(), List.of()),
                        children, onRoster))
                .toList();
        String nextBefore = hasMore ? String.valueOf(checklists.get(checklists.size() - 1).getId()) : null;
        return new SentChecklistPageResponse(responses, nextBefore);
    }

    @Transactional
    public void cancel(String userId, Long checklistId) {
        Long organizationId = userService.getOrganizationIdOf(userId);
        int canceled = checklistRepository.cancelIfActive(checklistId, organizationId, LocalDateTime.now(clock));
        if (canceled == 0 && !checklistRepository.existsByIdAndOrganizationId(checklistId, organizationId)) {
            throw notFound();
        }
    }

    static ChecklistException notFound() {
        return new ChecklistException(ChecklistErrorCode.CHECKLIST_NOT_FOUND);
    }

    private Map<Long, Child> childrenById(Collection<Long> childIds) {
        return childRepository.findAllById(Set.copyOf(childIds)).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
    }

    private Set<Long> onRoster(Long organizationId, Map<Long, Child> children) {
        Set<Long> alive = children.values().stream()
                .filter(child -> !child.isDeleted())
                .map(Child::getId)
                .collect(Collectors.toSet());
        if (alive.isEmpty()) {
            return Set.of();
        }
        return childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(alive).stream()
                .filter(link -> link.getOrganizationId().equals(organizationId))
                .map(ChildOrganization::getChildId)
                .collect(Collectors.toSet());
    }

    private static SentChecklistResponse toResponse(Checklist checklist, List<ChecklistTarget> targets,
            Map<Long, Child> children, Set<Long> onRoster) {
        List<SentChecklistResponse.Target> rows = targets.stream()
                .sorted(Comparator.comparing(ChecklistTarget::getChildId))
                .map(target -> {
                    Child child = children.get(target.getChildId());
                    return new SentChecklistResponse.Target(
                            new SentChecklistResponse.ChildRef(String.valueOf(target.getChildId()),
                                    child == null ? null : child.getName()),
                            onRoster.contains(target.getChildId()),
                            target.getConfirmedAt());
                })
                .toList();
        int total = (int) rows.stream().filter(SentChecklistResponse.Target::onRoster).count();
        int confirmed = (int) rows.stream().filter(row -> row.onRoster() && row.confirmedAt() != null).count();
        return new SentChecklistResponse(String.valueOf(checklist.getId()), checklist.getItems(),
                checklist.getCreatedAt(), total, confirmed, rows);
    }
}
