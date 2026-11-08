package com.itda.backend.service.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.itda.backend.domain.child.ChildOrganization;
import com.itda.backend.domain.consent.ConsentState;
import com.itda.backend.dto.response.agent.AiProgressItem;
import com.itda.backend.dto.response.dashboard.DashboardAiProgressResponse.Item;
import com.itda.backend.dto.response.dashboard.DashboardPipelineResponse.Kind;
import com.itda.backend.repository.child.ChildOrganizationRepository;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.ProgressEntryKey;
import com.itda.backend.service.consent.ConsentStateReader;

import lombok.RequiredArgsConstructor;

/**
 * AI {@code GET /progress} 의 줄을 기관별 진행 현황 줄로 바꾼다 (#453).
 *
 * <ul>
 *   <li>매칭 — 일지 → 기관·날짜. 진행 현황의 "아이 찾는 중" 줄은 날짜마다 한 줄이라 아이 없이 붙인다</li>
 *   <li>검증 — 일지 → 기관·아이·날짜</li>
 *   <li>요약 — AI 가 기관·아이·날짜를 그대로 준다</li>
 *   <li>리포트 — 아이 → 지금 명부에 있고 공유 동의가 켜진 기관 모두. 진행 현황(O-80)이 주 줄을 보여 주는 기준과 같다 —
 *       동의하지 않은 기관에는 화면에 안 그려지더라도 응답에 넣지 않는다</li>
 * </ul>
 * 같은 줄에 일이 여럿이면 하나만 남긴다.
 * <ul>
 *   <li>다른 에이전트끼리는 <b>더 이른 에이전트</b> — 진행 현황(O-80)이 묶음에 검증 중인 일지가 하나라도 있으면 그 줄을
 *       "AI 확인"으로 두므로({@code DashboardPipelineService#mainStatusOf}) 문구도 그 단계를 따른다</li>
 *   <li>같은 에이전트끼리는 <b>가장 앞선 노드</b> — #452 의 노드 순서에서 뒤일수록 앞선 것. 모르는 노드는 가장 뒤처진
 *       것으로 본다</li>
 *   <li>그래도 같으면 더 오래 걸린 것</li>
 * </ul>
 *
 * <p>DB 는 한 번에 최대 세 번 읽는다(일지 묶음 · 아이-기관 연결 묶음 · 동의 묶음). 그 에이전트가 안 돌면 읽지 않는다.
 * 키가 빠졌거나 모르는 에이전트·찾지 못한 일지는 버린다 — 붙일 줄이 없다.
 */
@Component
@RequiredArgsConstructor
public class AiProgressTranslator {

    /** 에이전트 순서. 알림(notify)은 진행 현황 줄이 이미 끝난 뒤라 넣지 않는다. */
    private static final List<String> AGENTS = List.of("matching", "validation", "summary", "insight");

    /** 에이전트마다 노드 순서 (#452). 리포트의 {@code find_claims} 는 {@code refine} 안에서 도구를 부르는 동안이다. */
    private static final Map<String, List<String>> NODES = Map.of(
            "matching", List.of("extract", "shortlist", "llm_judge", "decide"),
            "validation", List.of("perceive", "plan", "act", "reflect"),
            "summary", List.of("gather", "write", "ground", "assemble"),
            "insight", List.of("perceive", "plan", "act", "reflect", "refine", "find_claims"));

    /** 클수록 남긴다. 에이전트는 이른 쪽이 크다. */
    private static final Comparator<Item> PREFERRED = Comparator
            .comparingInt((Item item) -> -AGENTS.indexOf(item.agent()))
            .thenComparingInt(item -> NODES.get(item.agent()).indexOf(item.node()))
            .thenComparingInt(Item::elapsedSec);

    private final JournalEntryRepository journalEntryRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;

    /** 기관 id → 그 기관 진행 현황에 붙일 줄. AI 가 준 순서(시작한 순서)를 지킨다. */
    public Map<Long, List<Item>> translate(List<AiProgressItem> items) {
        List<AiProgressItem> known = items.stream().filter(AiProgressTranslator::isComplete).toList();
        Map<Long, ProgressEntryKey> entries = entriesOf(known);
        Map<Long, List<Long>> organizationsByChild = organizationsOf(known);

        Map<RowKey, Item> rows = new LinkedHashMap<>();
        for (AiProgressItem item : known) {
            for (Placed placed : place(item, entries, organizationsByChild)) {
                rows.merge(placed.key(), placed.item(), (a, b) -> PREFERRED.compare(a, b) >= 0 ? a : b);
            }
        }
        Map<Long, List<Item>> byOrganization = new LinkedHashMap<>();
        rows.forEach((key, item) -> byOrganization.computeIfAbsent(key.organizationId(), id -> new ArrayList<>())
                .add(item));
        return byOrganization;
    }

    private static boolean isComplete(AiProgressItem item) {
        // AGENTS 에 없는 에이전트는 먼저 버린다 — 고르는 순서(PREFERRED)가 AGENTS · NODES 를 그대로 읽는다.
        // 새 에이전트를 붙이면 AGENTS · NODES · 아래 switch 를 함께 고친다.
        if (item.agent() == null || !AGENTS.contains(item.agent())) {
            return false;
        }
        return switch (item.agent()) {
            case "matching", "validation" -> item.journalEntryId() != null;
            case "summary" -> item.childId() != null && item.entryDate() != null && item.institutionId() != null;
            case "insight" -> item.childId() != null && item.periodFrom() != null;
            default -> false;
        };
    }

    private Map<Long, ProgressEntryKey> entriesOf(List<AiProgressItem> items) {
        Set<Long> ids = items.stream().map(AiProgressItem::journalEntryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return journalEntryRepository.findProgressKeys(ids).stream()
                .collect(Collectors.toMap(ProgressEntryKey::id, Function.identity(), (a, b) -> a));
    }

    private Map<Long, List<Long>> organizationsOf(List<AiProgressItem> items) {
        Set<Long> children = items.stream().filter(item -> "insight".equals(item.agent()))
                .map(AiProgressItem::childId).collect(Collectors.toSet());
        if (children.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.statesOf(children);
        return childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(children).stream()
                .filter(link -> consents.getOrDefault(link.getChildId(), Map.of())
                        .getOrDefault(link.getOrganizationId(), ConsentState.NOT_GRANTED) == ConsentState.GRANTED)
                .collect(Collectors.groupingBy(ChildOrganization::getChildId,
                        Collectors.mapping(ChildOrganization::getOrganizationId, Collectors.toList())));
    }

    private static List<Placed> place(AiProgressItem item, Map<Long, ProgressEntryKey> entries,
            Map<Long, List<Long>> organizationsByChild) {
        return switch (item.agent()) {
            case "matching", "validation" -> {
                ProgressEntryKey entry = entries.get(item.journalEntryId());
                Long organizationId = entry == null ? null : parseId(entry.institutionId());
                if (organizationId == null) {
                    yield List.of();
                }
                // 매칭 줄은 아이를 찾는 날짜 줄이다 — 매칭 중에 아이가 정해져도 그 줄에 붙인다.
                Long childId = "matching".equals(item.agent()) ? null : entry.childId();
                yield List.of(placed(organizationId, Kind.ENTRY, childId, entry.entryDate(), null, item));
            }
            case "summary" -> List.of(
                    placed(item.institutionId(), Kind.ENTRY, item.childId(), item.entryDate(), null, item));
            case "insight" -> organizationsByChild.getOrDefault(item.childId(), List.of()).stream()
                    .map(organizationId -> placed(organizationId, Kind.WEEK, item.childId(), null,
                            item.periodFrom(), item))
                    .toList();
            default -> List.of();
        };
    }

    private static Placed placed(Long organizationId, Kind kind, Long childId, LocalDate date, LocalDate weekFrom,
            AiProgressItem item) {
        String child = childId == null ? null : String.valueOf(childId);
        return new Placed(new RowKey(organizationId, kind, child, date, weekFrom),
                new Item(kind, child, date, weekFrom, item.agent(), item.node(), item.elapsedSec()));
    }

    /** {@code raw_record.institution_id} 는 문자열이다. 숫자가 아니면 기관 사용자가 볼 줄이 아니다. */
    private static Long parseId(String institutionId) {
        try {
            return institutionId == null ? null : Long.valueOf(institutionId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record RowKey(Long organizationId, Kind kind, String childId, LocalDate date, LocalDate weekFrom) {
    }

    private record Placed(RowKey key, Item item) {
    }
}
