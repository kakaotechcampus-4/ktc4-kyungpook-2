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
// 🚨 팀원 리뷰(병합 전 수정 필요, 아직 미반영): matching_result는 organization_id를 직접
// 갖지 않아서 journal_entry -> raw_record -> organization을 거쳐야 하는데, 기관별 접근
// 제한이 아직 안 걸려있다 — 로그인만 하면 다른 기관 소속 매칭 결과까지 전부 조회/처리
// 가능한 상태. JournalEntry/ChildOrganization 엔티티는 이제 있어서 연결 경로 자체는 만들
// 수 있다(더 이상 엔티티 부재로 막혀있지 않음) — 다음 작업으로 실제 스코핑 추가 예정.
// 단, raw_record.institutionId가 아직 카카오ID 임시값이라(PR #43 머지 전) 완전한 검증은
// 그 이후에나 의미가 있다. 실제 운영 트래픽엔 그 전까지 열어두면 안 된다.
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
