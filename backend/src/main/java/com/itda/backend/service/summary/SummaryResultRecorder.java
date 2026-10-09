package com.itda.backend.service.summary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.JournalEntry;
import com.itda.backend.domain.JournalEntryStatus;
import com.itda.backend.domain.SummaryResult;
import com.itda.backend.dto.request.SummaryAgentRequest;
import com.itda.backend.dto.response.SummaryAgentResponse;
import com.itda.backend.repository.JournalEntryRepository;
import com.itda.backend.repository.SummaryResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** AI 요약 결과를 summary_result 와 journal_entry 에 쓰는 곳은 여기 하나다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryResultRecorder {

    private final JournalEntryRepository journalEntryRepository;
    private final SummaryResultRepository summaryResultRepository;
    private final SummaryEvidenceChecker evidenceChecker;

    /**
     * 요약을 저장하고 묶음의 일지를 Gate 1 대기로 보낸다. 요약에 반영되지 않은 일지(uncovered)도 같은 요약을
     * 가리킨다 — 빠졌다는 사실을 Gate 1 에서 볼 수 있게.
     *
     * <p>그 묶음에 승인 전 요약이 있으면 같은 판을 덮어쓰고, 승인된 판만 있으면 다음 판으로 쌓는다 (DB 스키마 §8.2).
     *
     * <p>200 이어도 본문이 비어 올 수 있다(근거를 못 찾은 문장을 AI 가 모두 버린 경우). 교사가 검토할 글이 없으므로
     * 저장하지 않고 호출 실패와 같이 처리한다. 근거 재검사({@link SummaryEvidenceChecker})에 걸려도 같다.
     *
     * @param request AI 에 실제로 보낸 요청. 근거는 DB 를 다시 읽은 원문이 아니라 보낸 원문과 대조한다
     */
    @Transactional
    public void record(ClaimedSummaryGroup claimed, SummaryAgentRequest request, SummaryAgentReply reply) {
        SummaryAgentResponse response = reply.response();
        if (response.content() == null || response.content().isBlank()) {
            log.warn("summary returned empty content, recorded as failure group={}", claimed.group());
            recordFailure(claimed);
            return;
        }
        List<JournalEntry> entries = summarizingEntries(claimed);
        if (entries.isEmpty()) {
            log.warn("all journal entries deleted during summary, result dropped group={}", claimed.group());
            return;
        }
        // 보낸 원문 중 아직 남아 있는 일지만 재료로 본다. 요약하는 사이 삭제된 일지를 근거로 들면 요약 전체를 버린다.
        Set<Long> alive = entries.stream().map(JournalEntry::getId).collect(Collectors.toSet());
        Map<Long, String> sources = request.sources().stream()
                .filter(source -> alive.contains(source.journalEntryId()))
                .collect(Collectors.toMap(SummaryAgentRequest.Source::journalEntryId,
                        SummaryAgentRequest.Source::content));
        Optional<String> problem = evidenceChecker.findProblem(claimed.group(), sources, response);
        if (problem.isPresent()) {
            // 사유에는 위치와 이유만 있다. 본문·인용 원문은 아이 기록이라 로그에 남기지 않는다.
            log.warn("summary evidence check failed, recorded as failure group={} journalEntryIds={} reason={}",
                    claimed.group(), claimed.journalEntryIds(), problem.get());
            recordFailure(claimed);
            return;
        }

        SummaryGroup group = claimed.group();
        String claims = toJson(response.claims());
        String covered = toJson(response.coveredEntryIds());
        String uncovered = toJson(response.uncoveredEntryIds());
        SummaryResult latest = summaryResultRepository
                .findFirstByChildIdAndEntryDateAndInstitutionIdOrderByRevisionDesc(
                        group.childId(), group.entryDate(), group.institutionId())
                .orElse(null);

        SummaryResult summary;
        if (latest != null && latest.isBeforeApproval()) {
            latest.overwrite(response.content(), claims, covered, uncovered, reply.rawJson());
            summary = latest;
        } else {
            int revision = latest == null ? 1 : latest.getRevision() + 1;
            summary = summaryResultRepository.save(SummaryResult.of(group.childId(), group.entryDate(),
                    group.institutionId(), revision, response.content(), claims, covered, uncovered,
                    reply.rawJson()));
        }
        entries.forEach(entry -> entry.completeSummary(summary.getId()));
    }

    /**
     * 호출이 실패했다. 처음 요약하던 일지는 FAILED 로, 이미 승인 전 요약에 들어가 있던 일지는 그 요약이 그대로
     * 유효하므로 Gate 1 대기로 돌린다. 요약에는 실패 상태가 없어 행을 남기지 않는다 — FAILED 일지의 검증 결과가
     * PASS·REVIEW 면 요약 단계에서 실패한 것이다.
     */
    @Transactional
    public void recordFailure(ClaimedSummaryGroup claimed) {
        summarizingEntries(claimed).forEach(JournalEntry::failSummary);
    }

    // 요약하는 사이 삭제됐거나 다른 경로로 상태가 바뀐 일지는 건드리지 않는다.
    private List<JournalEntry> summarizingEntries(ClaimedSummaryGroup claimed) {
        return journalEntryRepository.findAllById(claimed.journalEntryIds()).stream()
                .filter(entry -> !entry.isDeleted())
                .filter(entry -> entry.getStatus() == JournalEntryStatus.SUMMARIZING)
                .toList();
    }

    // AI 가 보낸 JSON 조각을 그대로 문자열로 남긴다 — 화면이 원래 키로 읽는다.
    private static String toJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
