package com.itda.backend.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.MatchingQueueItemResponse;
import com.itda.backend.dto.MatchingResolveRequest;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.MatchingResultService;

import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md O-22, O-23.
// ponytail: matching_result는 organization_id를 직접 갖지 않아서(journal_entry ->
// raw_record -> organization을 거쳐야 함) 기관별 접근 제한을 아직 못 건다.
// JournalEntry/RawRecord 조회 경로가 생기면 여기서 소유권 검증을 추가할 것.
@RestController
@RequestMapping("/api/v1/matching-queue")
@RequiredArgsConstructor
public class MatchingQueueController {

    private final MatchingResultService matchingResultService;

    @GetMapping
    public ApiResponse<List<MatchingQueueItemResponse>> getQueue() {
        List<MatchingQueueItemResponse> queue = matchingResultService.getQueue().stream()
                .map(MatchingQueueItemResponse::from)
                .toList();
        return ApiResponse.success(queue);
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<MatchingQueueItemResponse> resolve(
            @PathVariable Long id, @RequestBody MatchingResolveRequest request) {
        var resolved = matchingResultService.resolve(id, request.action(), request.childId());
        return ApiResponse.success(MatchingQueueItemResponse.from(resolved));
    }
}
