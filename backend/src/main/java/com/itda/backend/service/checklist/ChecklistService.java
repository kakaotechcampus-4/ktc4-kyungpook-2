package com.itda.backend.service.checklist;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.itda.backend.domain.user.User;
import com.itda.backend.dto.response.checklist.SentChecklistPageResponse;
import com.itda.backend.dto.response.checklist.SentChecklistResponse;
import com.itda.backend.exception.checklist.ChecklistErrorCode;
import com.itda.backend.exception.checklist.ChecklistException;
import com.itda.backend.repository.checklist.ChecklistRepository;
import com.itda.backend.repository.checklist.ChecklistTargetRepository;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.user.UserRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 기관의 준비물·확인사항 — 보내기(O-70) · 보낸 목록(O-71) · 내리기(O-72).
 *
 * <p><b>"명부에 있다"</b>는 아이가 삭제되지 않았고 그 기관의 {@code child_organization} 이 살아 있다는 뜻이다. 보내기 검증과
 * 목록의 {@code onRoster} · 숫자가 이 기준 하나를 쓴다. 보호자에게 보이는 조건도 같다({@link GuardianChecklistService}).
 * 공유 동의와는 상관없다 — 동의는 아이 정보가 기관으로 가는 허락이고, 이 기능은 방향이 반대다.
 */
@Service
@RequiredArgsConstructor
public class ChecklistService {

    /** 보낸 목록 한 쪽. 더 오래된 요청은 {@code before} 로 이어 본다. */
    static final int PAGE_SIZE = 50;

    private final UserService userService;
    private final ChecklistRepository checklistRepository;
    private final ChecklistTargetRepository checklistTargetRepository;
    private final ChildRepository childRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 받을 아이가 모두 지금 명부에 있어야 보낸다. 하나라도 아니면 아무것도 저장하지 않고 400 이다. 받는 아이는 지금 정해진다 —
     * "명부 전체" 로 보냈어도 나중에 들어온 아이는 받지 않는다.
     */
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
        return toResponse(checklist, targets, children, onRoster, Map.of());
    }

    /**
     * 살아 있는 요청을 최신순 {@value #PAGE_SIZE}개씩. {@code before} 가 있으면 그 요청보다 오래된 쪽이다 — 기준 요청은 내린 것이어도
     * 우리 기관 요청이면 된다. 조회는 쪽마다 요청 · 받은 아이 · 아이 · 명부 · 보호자 이름 한 번씩이다.
     */
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
        Map<Long, String> confirmerNames = namesOf(allTargets.stream()
                .map(ChecklistTarget::getConfirmedBy).filter(Objects::nonNull).toList());

        List<SentChecklistResponse> responses = checklists.stream()
                .map(checklist -> toResponse(checklist, targetsByChecklist.getOrDefault(checklist.getId(), List.of()),
                        children, onRoster, confirmerNames))
                .toList();
        String nextBefore = hasMore ? String.valueOf(checklists.get(checklists.size() - 1).getId()) : null;
        return new SentChecklistPageResponse(responses, nextBefore);
    }

    /**
     * 통째로 내린다. 이미 내렸으면 아무것도 바꾸지 않는다(처음 시각이 남는다). 다른 기관 요청이거나 없으면 404 다.
     * 확인이 진행 중이면 그 확인이 커밋될 때까지 기다린다({@code ChecklistRepository.findByIdForShare}).
     */
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

    /** 삭제된 아이도 담는다 — 보낸 목록에서 "명부에서 빠짐" 으로 이름을 보여야 한다. */
    private Map<Long, Child> childrenById(Collection<Long> childIds) {
        return childRepository.findAllById(Set.copyOf(childIds)).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
    }

    /** 이 아이들 중 지금 우리 명부에 있는 아이 — 삭제되지 않았고 명부 행이 살아 있다. */
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

    private Map<Long, String> namesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(Set.copyOf(userIds)).stream()
                .filter(user -> user.getName() != null)
                .collect(Collectors.toMap(User::getId, User::getName));
    }

    private static SentChecklistResponse toResponse(Checklist checklist, List<ChecklistTarget> targets,
            Map<Long, Child> children, Set<Long> onRoster, Map<Long, String> confirmerNames) {
        List<SentChecklistResponse.Target> rows = targets.stream()
                .sorted(Comparator.comparing(ChecklistTarget::getChildId))
                .map(target -> {
                    Child child = children.get(target.getChildId());
                    return new SentChecklistResponse.Target(
                            new SentChecklistResponse.ChildRef(String.valueOf(target.getChildId()),
                                    child == null ? null : child.getName()),
                            onRoster.contains(target.getChildId()),
                            target.getConfirmedAt(),
                            target.getConfirmedBy() == null ? null : confirmerNames.get(target.getConfirmedBy()));
                })
                .toList();
        int total = (int) rows.stream().filter(SentChecklistResponse.Target::onRoster).count();
        int confirmed = (int) rows.stream().filter(row -> row.onRoster() && row.confirmedAt() != null).count();
        return new SentChecklistResponse(String.valueOf(checklist.getId()), checklist.getItems(),
                checklist.getCreatedAt(), total, confirmed, rows);
    }
}
