package com.itda.backend.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
//
// 🚨 팀원 리뷰(병합 전 수정 필요): matching_result는 organization_id를 직접 갖지 않는다
// (journal_entry -> raw_record -> organization을 거쳐야 하는데, JournalEntry 엔티티가
// 아직 없다). 그래서 지금은 로그인만 하면 다른 기관 소속 매칭 결과까지 전부 조회/처리
// 가능한 상태다 — JournalEntry 조회 경로가 생기기 전까지는 실제 운영 트래픽에 이 엔드
// 포인트를 열어두면 안 된다. develop에 머지하더라도 배포 전 별도 확인 필요.
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
            @PathVariable Long id,
            @RequestBody MatchingResolveRequest request,
            @AuthenticationPrincipal String reviewerId) {
        var resolved = matchingResultService.resolve(id, request.action(), request.childId(), reviewerId);
        return ApiResponse.success(MatchingQueueItemResponse.from(resolved));
    }
}
