package com.itda.backend.service.child;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.child.Child;
import com.itda.backend.domain.organization.Organization;
import com.itda.backend.domain.summary.ChildContext;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.response.child.GuardianJournalResponse;
import com.itda.backend.dto.response.child.ParentActivityResponse;
import com.itda.backend.dto.response.organization.InstitutionResponse;
import com.itda.backend.exception.child.ChildErrorCode;
import com.itda.backend.exception.child.ChildException;
import com.itda.backend.repository.child.ChildRepository;
import com.itda.backend.repository.invitation.ChildGuardianRepository;
import com.itda.backend.repository.organization.OrganizationRepository;
import com.itda.backend.repository.summary.ChildContextRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 보호자 일지 (api-spec G-20·G-21). 일지 한 건은 교사가 Gate 1 에서 승인한 요약 하나({@code child_context} 한 행)다.
 * 사람이 승인한 글만 보호자에게 간다. 날짜·기관은 {@code summary_result} 에서 채운다 (DB 스키마 §9.2).
 *
 * <p><b>동의가 꺼졌거나 명부에서 빠진 기관의 지난 일지도 보인다.</b> 동의 철회는 아이 기록이 다른 기관으로 가는 것을
 * 막는 장치이지 보호자가 자기 아이 이야기를 못 보게 하는 장치가 아니다 — 받은 인사이트와 같다 (§9.4).
 *
 * <p>{@code isNew} 는 승인된 지 24시간이 안 됐다는 뜻이다. 보호자가 읽었는지는 남기지 않는다.
 *
 * <p>상세는 아이 행을 공유 잠금으로 잡은 뒤 연결을 보므로 연결 해제와 줄을 선다 — 해제가 먼저 반영되면 404, 열람이
 * 먼저면 보여 준 뒤 해제된다 (§3.3). 목록은 잠그지 않는다. 해제와 겹치면 진행 중이던 목록 한 번은 나갈 수 있다
 * (보호자 인사이트 목록과 같다).
 *
 * <p>조회 횟수는 일지 수와 무관하다. 승인본 → 요약 → 기관 순서로 한 번씩 읽는다.
 */
@Service
@RequiredArgsConstructor
public class GuardianJournalQueryService {

    private static final Duration NEW_FOR = Duration.ofHours(24);
    private static final DateTimeFormatter ACTIVITY_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserService userService;
    private final ChildGuardianRepository childGuardianRepository;
    private final ChildRepository childRepository;
    private final ChildContextRepository childContextRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    /**
     * 아이 하나의 일지 전부. 관찰 날짜 최근 순, 같은 날은 승인 최근 순이다. 연결되지 않았거나 삭제된 아이면 홈과 같은
     * 403 이다.
     */
    @Transactional(readOnly = true)
    public List<GuardianJournalResponse> journal(String userId, Long childId) {
        Long parentId = userService.getParentIdOf(userId);
        if (!childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(childId, parentId)
                || childRepository.findByIdAndDeletedAtIsNull(childId).isEmpty()) {
            throw new ChildException(ChildErrorCode.CHILD_NOT_LINKED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 승인 최근 순으로 읽어 날짜로만 다시 줄 세운다. 정렬이 안정적이라 같은 날 안에서는 승인 순서가 남는다.
        return approved(childContextRepository.findByChildIdOrderByIdDesc(childId)).stream()
                .sorted(Comparator.comparing((Approved approved) -> approved.summary().getEntryDate()).reversed())
                .map(approved -> toResponse(approved, now))
                .toList();
    }

    /**
     * 일지 하나. 볼 수 없으면 이유를 나누지 않고 404 다.
     *
     * <p>아이 행을 공유 잠금으로 잡으므로 읽기 전용 트랜잭션이 아니다. 잠그기 전에 연결·아이를 엔티티로 읽지 않는다 —
     * 영속성 컨텍스트가 잠그기 전 값을 돌려줘 그사이 커밋된 해제를 놓친다. 승인본은 고치지 않는 테이블이라 아이 id 를
     * 알려고 먼저 읽어도 된다.
     */
    @Transactional
    public GuardianJournalResponse entry(String userId, Long journalId) {
        Long parentId = userService.getParentIdOf(userId);
        ChildContext context = childContextRepository.findById(journalId)
                .orElseThrow(GuardianJournalQueryService::notFound);
        Child child = childRepository.findByIdForShare(context.getChildId())
                .orElseThrow(GuardianJournalQueryService::notFound);
        if (child.isDeleted()
                || !childGuardianRepository.existsByChildIdAndUserIdAndDeletedAtIsNull(child.getId(), parentId)) {
            throw notFound();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return approved(List.of(context)).stream()
                .findFirst()
                .map(approved -> toResponse(approved, now))
                .orElseThrow(GuardianJournalQueryService::notFound);
    }

    /**
     * 보호자 홈의 "최근 공유 활동". 승인된 일지마다 한 줄, 승인 최근 순 5건이다. 시각은 승인 시각 — 기관에서 보호자에게
     * 도착한 때다. 연결 확인은 부르는 쪽(홈)이 마쳤다.
     */
    @Transactional(readOnly = true)
    public List<ParentActivityResponse> recentActivity(Long childId) {
        return approved(childContextRepository.findTop5ByChildIdOrderByIdDesc(childId)).stream()
                .map(GuardianJournalQueryService::toActivity)
                .toList();
    }

    /** {@code contexts} 순서대로. 요약이나 기관 행이 없는 승인본은 뺀다 — 이름을 모르는 기관을 화면에 올리지 않는다. */
    private List<Approved> approved(List<ChildContext> contexts) {
        if (contexts.isEmpty()) {
            return List.of();
        }
        Map<Long, SummaryResult> summaries = summaryResultRepository
                .findAllById(contexts.stream().map(ChildContext::getSummaryResultId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SummaryResult::getId, Function.identity()));
        Map<Long, Organization> organizations = organizationRepository
                .findAllById(summaries.values().stream().map(SummaryResult::getInstitutionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Organization::getId, Function.identity()));
        return contexts.stream()
                .map(context -> {
                    SummaryResult summary = summaries.get(context.getSummaryResultId());
                    Organization institution = summary == null ? null : organizations.get(summary.getInstitutionId());
                    return institution == null ? null : new Approved(context, summary, institution);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static GuardianJournalResponse toResponse(Approved approved, LocalDateTime now) {
        ChildContext context = approved.context();
        return new GuardianJournalResponse(
                String.valueOf(context.getId()),
                String.valueOf(context.getChildId()),
                InstitutionResponse.from(approved.institution()),
                approved.summary().getEntryDate().toString(),
                context.getCreatedAt().isAfter(now.minus(NEW_FOR)),
                context.getContent());
    }

    private static ParentActivityResponse toActivity(Approved approved) {
        String journalId = String.valueOf(approved.context().getId());
        LocalDate date = approved.summary().getEntryDate();
        return new ParentActivityResponse(
                journalId,
                approved.context().getCreatedAt().format(ACTIVITY_AT),
                "%s에서 %d월 %d일 일지가 도착했어요".formatted(
                        approved.institution().getName(), date.getMonthValue(), date.getDayOfMonth()),
                journalId);
    }

    private static ChildException notFound() {
        return new ChildException(ChildErrorCode.JOURNAL_NOT_FOUND);
    }

    /** 승인본 하나와 그 날짜·기관. */
    private record Approved(ChildContext context, SummaryResult summary, Organization institution) {
    }
}
