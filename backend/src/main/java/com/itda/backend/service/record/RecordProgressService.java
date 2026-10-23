package com.itda.backend.service.record;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.itda.backend.domain.record.FailureStage;
import com.itda.backend.domain.record.JournalEntry;
import com.itda.backend.domain.record.JournalEntryStatus;
import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.domain.validation.ValidationResult;
import com.itda.backend.domain.validation.ValidationVerdict;
import com.itda.backend.dto.response.record.RecordProgressResponse;
import com.itda.backend.dto.response.record.RecordProgressResponse.EntryProgress;
import com.itda.backend.repository.record.JournalEntryRepository;
import com.itda.backend.repository.record.RawRecordRepository;
import com.itda.backend.repository.validation.ValidationResultRepository;
import com.itda.backend.service.user.UserService;

import lombok.RequiredArgsConstructor;

/**
 * 업로드한 파일이 지금 어디까지 갔는지 (api-spec O-26). 읽기만 하고 아무것도 바꾸지 않는다.
 *
 * <p>교사는 업로드 뒤 화면이 "기록 등록 중" 에 멈춰 있는 것만 보고 기다렸다 (멘토 리뷰 #191).
 *
 * <p>파일 수와 상관없이 쿼리가 두 번이다 — 파일 목록 한 번, 그 파일들의 일지 한 번.
 * 실패한 일지가 있으면 어디서 죽었는지 되짚느라 한 번 더 본다.
 */
@Service
@RequiredArgsConstructor
public class RecordProgressService {

    /** 프론트 {@code PIPELINE_STAGES} 순서. 화면 라벨과 1:1 이라 숫자를 바꾸면 화면이 어긋난다. */
    private static final int STAGE_UPLOADED = 0;
    private static final int STAGE_MATCHING = 1;
    private static final int STAGE_VALIDATING = 2;
    private static final int STAGE_SUMMARIZING = 3;
    private static final int STAGE_GATE1 = 4;

    /**
     * 한 번에 보여줄 파일 수. 화면은 "최근에 올린 것이 어떻게 됐나" 를 보는 자리라 전부 내릴 필요가 없고,
     * 파일마다 일지를 또 끌고 와서 상한이 없으면 한 학기 치가 한 응답에 담긴다.
     *
     * <p>ponytail: 더 거슬러 봐야 하면 그때 페이징을 넣는다. 지금 넣으면 쓰지 않는 계약이 하나 는다.
     */
    private static final Limit RECENT_FILES = Limit.of(50);

    private static final String RUNNING = "running";
    private static final String WAITING = "waiting";
    private static final String FAILED = "failed";
    private static final String DONE = "done";

    private final UserService userService;
    private final RawRecordRepository rawRecordRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final ValidationResultRepository validationResultRepository;

    /** 우리 기관이 올린 파일들. 최근 업로드가 먼저 온다. */
    @Transactional(readOnly = true)
    public List<RecordProgressResponse> getProgress(String userId) {
        String institutionId = String.valueOf(userService.getOrganizationIdOf(userId));
        List<RawRecord> records =
                rawRecordRepository.findByInstitutionIdAndDeletedAtIsNullOrderByIdDesc(institutionId, RECENT_FILES);
        if (records.isEmpty()) {
            return List.of();
        }

        List<JournalEntry> entries = journalEntryRepository
                .findByRawRecordIdInAndDeletedAtIsNullOrderByIdAsc(records.stream().map(RawRecord::getId).toList());
        Map<Long, List<JournalEntry>> entriesByRecord =
                entries.stream().collect(Collectors.groupingBy(JournalEntry::getRawRecordId));
        Set<Long> validatedIds = validatedAmong(entries);

        return records.stream()
                .map(record -> new RecordProgressResponse(
                        String.valueOf(record.getId()),
                        record.getOriginalFilename(),
                        record.getCreatedAt(),
                        entriesByRecord.getOrDefault(record.getId(), List.of()).stream()
                                .map(entry -> progressOf(entry, validatedIds))
                                .toList()))
                .toList();
    }

    /**
     * 실패한 일지 중 <b>검증까지는 통과한</b> 것.
     *
     * <p>⚠️ <b>행이 있다는 것만으로는 안 된다.</b> 검증 호출이 실패해도 {@code verdict = FAILED} 로 행이
     * 남는다 (#122 — 행이 없던 시절 매칭 실패와 구분이 안 돼 PR #108 버그가 났다). 그래서 판정까지 본다 —
     * {@code FAILED} 가 아닌 판정이 있어야 검증을 지난 것이다.
     *
     * <p>실패한 일지가 없으면 묻지 않는다. 목록 대부분이 정상이라 평소에는 쿼리가 두 번으로 끝난다.
     */
    private Set<Long> validatedAmong(List<JournalEntry> entries) {
        List<Long> failedIds = entries.stream()
                .filter(entry -> entry.getStatus() == JournalEntryStatus.FAILED)
                .map(JournalEntry::getId)
                .toList();
        if (failedIds.isEmpty()) {
            return Set.of();
        }
        return validationResultRepository.findByJournalEntryIdInOrderByIdDesc(failedIds).stream()
                .filter(result -> result.getVerdict() != ValidationVerdict.FAILED)
                .map(ValidationResult::getJournalEntryId)
                .collect(Collectors.toSet());
    }

    private static EntryProgress progressOf(JournalEntry entry, Set<Long> validatedIds) {
        return new EntryProgress(String.valueOf(entry.getId()), stageOf(entry, validatedIds),
                stateOf(entry.getStatus()));
    }

    /**
     * 어느 단계에 있는지.
     *
     * <p>시스템 오류는 멈춘 단계가 {@code failure_stage} 에 적혀 있다(#180). 요약 실패는 요약 단계다.
     *
     * <p>{@code FAILED} 는 <b>어디서 실패했는지 적힌 칸이 없다.</b> 그대로 0 으로 내려보내면 요약까지 갔다가
     * 죽은 기록이 "등록 단계 실패" 로 보인다. 일지가 남긴 흔적으로 되짚는다. #180 부터는 매칭 실패만 FAILED 가
     * 되지만, 그 전에 검증·요약에서 FAILED 로 남은 일지가 있을 수 있어 되짚기를 둔다.
     */
    private static int stageOf(JournalEntry entry, Set<Long> validatedIds) {
        return switch (entry.getStatus()) {
            case PENDING -> STAGE_UPLOADED;
            case MATCHING, MATCH_REVIEW, CONSENT_BLOCKED, EXCLUDED -> STAGE_MATCHING;
            case MATCHED, VALIDATING, VALIDATION_BLOCKED, REUPLOAD_REQUESTED, VALIDATION_HELD -> STAGE_VALIDATING;
            case VALIDATED, SUMMARIZING -> STAGE_SUMMARIZING;
            case GATE1_PENDING, GATE1_HELD, COMPLETED -> STAGE_GATE1;
            case SUMMARY_FAILED -> STAGE_SUMMARIZING;
            case SYSTEM_ERROR -> entry.getFailureStage() == FailureStage.SUMMARY ? STAGE_SUMMARIZING : STAGE_VALIDATING;
            case FAILED -> failedStageOf(entry, validatedIds);
        };
    }

    /**
     * 실패한 일지가 어디까지 갔었는지. 일지가 남긴 흔적만으로 되짚는다.
     *
     * <pre>
     * 검증을 지난 판정이 있다  →  요약에서 죽었다
     * 아동이 정해졌다          →  검증에서 죽었다
     * 그 외                    →  매칭에서 죽었다
     * </pre>
     *
     * <p><b>{@code summary_id} 는 보지 않는다.</b> 보고 싶지만 볼 수가 없다 — {@code failSummary()} 는
     * 번호가 <i>없을 때만</i> {@code FAILED} 로 가고, 번호가 있으면 {@code GATE1_PENDING} 으로 간다.
     * 즉 {@code FAILED} 이면서 번호가 있는 일지는 생기지 않는다.
     */
    private static int failedStageOf(JournalEntry entry, Set<Long> validatedIds) {
        if (validatedIds.contains(entry.getId())) {
            return STAGE_SUMMARIZING;
        }
        return entry.getChildId() != null ? STAGE_VALIDATING : STAGE_MATCHING;
    }

    /**
     * 계약의 네 값 중 하나로 접는다 (api-spec O-26).
     *
     * <p>⚠️ {@code EXCLUDED}·{@code REUPLOAD_REQUESTED}·{@code VALIDATION_HELD} 는 <b>끝났지만 완료는
     * 아니다</b> — 아동 맥락에 올라가지 않는다. 그런데 갈 값이 {@code done} 밖에 없어서 교사에게는
     * {@code COMPLETED} 와 같아 보인다. 화면이 구분해야 하면 칸을 더해야 한다 (#210 에 적어 둠).
     *
     * <p>{@code CONSENT_BLOCKED} 는 지금 아무 데서도 쓰지 않는다. 쓰게 되면 {@code waiting} 이 맞는지
     * 다시 봐야 한다 — 동의는 보호자가 하는 일이라 교사는 치울 큐가 없는데 기다리라고만 보인다.
     */
    private static String stateOf(JournalEntryStatus status) {
        return switch (status) {
            case PENDING, MATCHING, MATCHED, VALIDATING, VALIDATED, SUMMARIZING -> RUNNING;
            case MATCH_REVIEW, CONSENT_BLOCKED, VALIDATION_BLOCKED, GATE1_PENDING, GATE1_HELD -> WAITING;
            case EXCLUDED, REUPLOAD_REQUESTED, VALIDATION_HELD, COMPLETED -> DONE;
            case FAILED, SUMMARY_FAILED, SYSTEM_ERROR -> FAILED;
        };
    }
}
