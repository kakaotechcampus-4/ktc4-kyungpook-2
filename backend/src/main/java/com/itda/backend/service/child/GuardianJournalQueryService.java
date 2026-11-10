package com.itda.backend.service.child;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.itda.backend.dto.response.child.GuardianJournalDetailResponse;
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

@Service
@RequiredArgsConstructor
public class GuardianJournalQueryService {

    private static final Duration NEW_FOR = Duration.ofHours(24);

    private final UserService userService;
    private final GuardianChildGuard guardianChildGuard;
    private final ChildGuardianRepository childGuardianRepository;
    private final ChildRepository childRepository;
    private final ChildContextRepository childContextRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<GuardianJournalResponse> journal(String userId, Long childId) {
        return journal(userId, childId, null);
    }

    @Transactional(readOnly = true)
    public List<GuardianJournalResponse> journal(String userId, Long childId, Integer limit) {
        Long parentId = userService.getParentIdOf(userId);
        guardianChildGuard.requireLinkedChild(parentId, childId);
        LocalDateTime now = LocalDateTime.now(clock);
        return ordered(childId).stream()
                .limit(limit == null ? Long.MAX_VALUE : limit)
                .map(approved -> toResponse(approved, now))
                .toList();
    }

    @Transactional
    public GuardianJournalDetailResponse entry(String userId, Long journalId) {
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
        List<Approved> ordered = ordered(child.getId());
        int at = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).context().getId().equals(context.getId())) {
                at = i;
                break;
            }
        }
        if (at == -1) {
            throw notFound();
        }
        Approved self = ordered.get(at);
        String nextId = at > 0 ? String.valueOf(ordered.get(at - 1).context().getId()) : null;
        String prevId = at < ordered.size() - 1 ? String.valueOf(ordered.get(at + 1).context().getId()) : null;
        List<GuardianJournalResponse> sameDay = ordered.stream()
                .filter(other -> other.summary().getEntryDate().equals(self.summary().getEntryDate())
                        && !other.institution().getId().equals(self.institution().getId()))
                .map(other -> toResponse(other, now))
                .toList();
        return GuardianJournalDetailResponse.of(toResponse(self, now), prevId, nextId, sameDay);
    }

    private List<Approved> ordered(Long childId) {
        return approved(childContextRepository.findByChildIdOrderByIdDesc(childId)).stream()
                .sorted(Comparator.comparing((Approved approved) -> approved.summary().getEntryDate()).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ActivityLine> recentActivity(Long childId) {
        return approved(childContextRepository.findTop5ByChildIdOrderByIdDesc(childId)).stream()
                .map(GuardianJournalQueryService::toActivity)
                .toList();
    }

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

    private static ActivityLine toActivity(Approved approved) {
        LocalDate date = approved.summary().getEntryDate();
        LocalDateTime at = approved.context().getCreatedAt();
        return new ActivityLine(at, ParentActivityResponse.journal(
                String.valueOf(approved.context().getId()),
                at,
                "%s에서 %d월 %d일 일지가 도착했어요".formatted(
                        approved.institution().getName(), date.getMonthValue(), date.getDayOfMonth())));
    }

    private static ChildException notFound() {
        return new ChildException(ChildErrorCode.JOURNAL_NOT_FOUND);
    }

    private record Approved(ChildContext context, SummaryResult summary, Organization institution) {
    }
}
