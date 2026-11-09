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

/**
 * 보호자의 준비물·확인사항 — 목록(G-50) · 확인(G-51).
 *
 * <p>이 아이와 연결된 보호자이고 아이가 삭제되지 않았을 때만 쓴다. 아니면 이유를 나누지 않고 403 이다(보호자 아이 조회와 같다).
 * 확인은 아이 행을 공유 잠금으로 먼저 잡은 뒤 이 조건을 본다 — 연결 해제(아이 행 FOR UPDATE)와 줄을 선다.
 * 보이는 요청은 내리지 않았고 보낸 기관 명부에 아이가 지금 있는 것이다. 보낸 뒤에 연결된 보호자도 본다 — 요청은 보호자가
 * 아니라 아이에게 간 것이다.
 */
@Service
@RequiredArgsConstructor
public class GuardianChecklistService {

    /** 확인한 요청은 최근 이만큼만. 확인 안 한 요청은 상한 없이 모두 보인다 — 잘려서 확인할 수 없게 되면 안 된다. */
    static final int CONFIRMED_LIMIT = 20;

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ChecklistRepository checklistRepository;
    private final ChecklistTargetRepository checklistTargetRepository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    /**
     * 확인 안 한 요청 전부(최신순) 뒤에 확인한 요청 최근 {@value #CONFIRMED_LIMIT}개(최신순). {@code needsCheckOnly} 면 확인 안 한
     * 것만 — 벨 · 설정이 화면마다 부르는 경로라 DB 에서 미확인만 거른다.
     */
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

    /**
     * 이 아이 몫으로 확인한다. 한 번뿐이고 되돌리지 않는다. 이미 다른 보호자가 확인했으면 그 확인을 그대로 돌려준다(200).
     *
     * <p>잠금 순서는 아이 → 요청 → 받은 줄이다 (DB 스키마 §3.3).
     * <ul>
     *   <li>아이 행을 공유 잠금으로 잡은 뒤 삭제 · 보호자 연결을 본다. 연결 해제(G-05 · O-19)는 아이 행을 FOR UPDATE 로 먼저
     *       잠그므로 이 잠금과 줄을 선다 — 해제가 먼저 커밋되면 여기서 403 이고 확인은 남지 않는다. 확인이 먼저면 해제는 확인이
     *       커밋된 뒤 진행된다.</li>
     *   <li>요청 행을 공유 잠금으로 잡은 채 내렸는지 본다. 내리기(조건부 UPDATE)는 같은 행을 바꾸므로 이 잠금과 줄을 선다 —
     *       내리기가 먼저 커밋되면 404, 확인이 먼저면 내리기는 확인이 커밋된 뒤 내린다.</li>
     * </ul>
     * 잠그기 전에 아이 · 요청 · 받은 줄을 엔티티로 읽지 않는다 — 영속성 컨텍스트가 잠그기 전 값을 돌려준다.
     */
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
        // UPDATE 가 영속성 컨텍스트를 비웠다. 1행이든 0행이든 다시 읽은 값으로 답한다
        ChecklistTarget target = checklistTargetRepository.findById(targetId).orElseThrow(ChecklistService::notFound);
        return assemble(List.of(new GuardianChecklistRow(target, checklist))).get(0);
    }

    /** 기관을 한 번씩 모아 읽는다. */
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
