package com.itda.backend.service.summary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.itda.backend.domain.record.FailureReason;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.summary.SummaryResult;
import com.itda.backend.dto.request.summary.SummaryAgentRequest;
import com.itda.backend.dto.response.summary.SummaryAgentResponse;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.summary.SummaryResultRepository;

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
    private final SummaryRunRequests runRequests;

    /**
     * 요약을 저장하고 묶음의 일지를 Gate 1 대기로 보낸다. 요약에 반영되지 않은 일지(uncovered)도 같은 요약을
     * 가리킨다 — 빠졌다는 사실을 Gate 1 에서 볼 수 있게.
     *
     * <p>그 묶음에 승인 전 요약이 있으면 같은 판을 덮어쓰고, 승인된 판만 있으면 다음 판으로 쌓는다 (DB 스키마 §8.2).
     *
     * <p>근거가 남은 문장이 없으면 AI 는 200 대신 503 을 준다(#148). 그래도 본문이 비어 오면 계약 위반이라 저장하지 않고
     * 시스템 오류(RESPONSE_UNREADABLE)로 남긴다. 근거 재검사({@link SummaryEvidenceChecker})에 걸리면
     * EVIDENCE_MISMATCH 다 (#180).
     *
     * @param request AI 에 실제로 보낸 요청. 근거는 DB 를 다시 읽은 원문이 아니라 보낸 원문과 대조한다
     */
    @Transactional
    public void record(ClaimedSummaryGroup claimed, SummaryAgentRequest request, SummaryAgentReply reply) {
        SummaryAgentResponse response = reply.response();
        // "지금 요약" 요청은 결과를 남길 때 지운다. 집을 때 지우면 AI 장애로 묶음이 되돌아갔을 때 요청까지 사라진다.
        runRequests.remove(claimed.group());
        if (isEmptyContent(response)) {
            log.warn("summary returned empty content, recorded as system error group={}", claimed.group());
            recordSystemError(claimed, FailureReason.RESPONSE_UNREADABLE);
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
        Set<Long> sentIds = request.sources().stream()
                .map(SummaryAgentRequest.Source::journalEntryId)
                .collect(Collectors.toSet());
        Optional<String> problem = evidenceChecker.findProblem(claimed.group(), sentIds, sources, response);
        if (problem.isPresent()) {
            // 사유에는 위치와 이유만 있다. 본문·인용 원문은 아이 기록이라 로그에 남기지 않는다.
            log.warn("summary evidence check failed, recorded as system error group={} journalEntryIds={} reason={}",
                    claimed.group(), claimed.journalEntryIds(), problem.get());
            recordSystemError(claimed, FailureReason.EVIDENCE_MISMATCH);
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
        // 필드가 없는 응답(#148 이전 AI)은 표시 없음으로 본다. 사유가 있는데 표시가 빠졌으면 경고 쪽으로 본다 —
        // 이름이 샜는데 경고가 안 뜨는 쪽이 더 나쁘다.
        JsonNode reasons = response.reviewReasons();
        boolean needsReview = Boolean.TRUE.equals(response.needsReview())
                || (reasons != null && reasons.isArray() && !reasons.isEmpty());
        summary.markReview(needsReview, toJson(reasons));
        entries.forEach(entry -> entry.completeSummary(summary.getId()));
    }

    /**
     * 재시도 뒤에도 근거가 남은 문장이 없다(AI 503 {@code no_grounded_claims}). 다시 요약하면 될 수 있으므로 처음 요약하던
     * 일지는 요약 실패(SUMMARY_FAILED)로 남긴다. 이미 승인 전 요약에 들어가 있던 일지는 그 요약이 그대로 유효하므로
     * Gate 1 대기로 돌린다. 요약에는 실패 상태가 없어 행을 남기지 않는다.
     */
    @Transactional
    public void recordFailure(ClaimedSummaryGroup claimed) {
        runRequests.remove(claimed.group());
        summarizingEntries(claimed).forEach(JournalEntry::failSummary);
    }

    /**
     * 다시 요약해도 같은 실패다(계약 위반·근거 재검사 실패·BE 오류). 처음 요약하던 일지는 시스템 오류로 멈추고 원인을
     * 남긴다. 이미 승인 전 요약에 들어가 있던 일지는 {@link #recordFailure} 와 같이 Gate 1 대기로 돌린다.
     */
    @Transactional
    public void recordSystemError(ClaimedSummaryGroup claimed, FailureReason reason) {
        runRequests.remove(claimed.group());
        summarizingEntries(claimed).forEach(entry -> entry.failSummaryBySystemError(reason));
    }

    // 본문이 빈 200 은 AI 계약에 없는 응답이다. "요약할 내용 없음"을 정상 결과로 받기로 하면(멘토 A3, AI #196 후속)
    // 그 표시가 붙은 빈 본문만 여기서 빼고 정상으로 처리한다.
    private static boolean isEmptyContent(SummaryAgentResponse response) {
        return response.content() == null || response.content().isBlank();
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
