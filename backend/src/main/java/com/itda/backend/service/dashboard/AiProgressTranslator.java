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

@Component
@RequiredArgsConstructor
public class AiProgressTranslator {

    private static final List<String> AGENTS = List.of("matching", "validation", "summary", "insight");

    private static final Map<String, List<String>> NODES = Map.of(
            "matching", List.of("extract", "shortlist", "llm_judge", "decide"),
            "validation", List.of("perceive", "plan", "act", "reflect"),
            "summary", List.of("gather", "write", "ground", "assemble"),
            "insight", List.of("perceive", "plan", "act", "reflect", "refine", "find_claims"));

    private static final Comparator<Item> PREFERRED = Comparator
            .comparingInt((Item item) -> -AGENTS.indexOf(item.agent()))
            .thenComparingInt(item -> NODES.get(item.agent()).indexOf(item.node()))
            .thenComparingInt(Item::elapsedSec);

    private final JournalEntryRepository journalEntryRepository;
    private final ChildOrganizationRepository childOrganizationRepository;
    private final ConsentStateReader consentStateReader;

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
        List<ChildOrganization> links = childOrganizationRepository.findByChildIdInAndDeletedAtIsNull(children);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Long, ConsentState>> consents = consentStateReader.insightStatesOf(
                links.stream().map(ChildOrganization::getChildId).collect(Collectors.toSet()));
        return links.stream()
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
