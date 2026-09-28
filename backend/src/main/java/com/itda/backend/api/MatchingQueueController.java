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
// 기관별 접근 제한: journal_entry -> raw_record.institutionId로 확인한다(RawRecordController와
// 같은 카카오ID 임시 패턴). child가 실제로 이 기관 소속인지는 ChildOrganization이 진짜
// organizationId를 쓰는데 지금은 kakaoId뿐이라 아직 못 비교한다 — PR #43 이후 과제.
@RestController
@RequestMapping("/api/v1/matching-queue")
@RequiredArgsConstructor
public class MatchingQueueController {

    private final MatchingResultService matchingResultService;

    @GetMapping
    public ApiResponse<List<MatchingQueueItemResponse>> getQueue(
            @AuthenticationPrincipal String institutionId) {
        return ApiResponse.success(matchingResultService.getQueue(institutionId));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<MatchingQueueItemResponse> resolve(
            @PathVariable Long id,
            @RequestBody MatchingResolveRequest request,
            @AuthenticationPrincipal String institutionId) {
        var resolved = matchingResultService.resolve(
                id, request.action(), request.childId(), institutionId, institutionId);
        return ApiResponse.success(resolved);
    }
}
