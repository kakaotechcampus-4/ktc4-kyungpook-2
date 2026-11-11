package com.itda.backend.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.consent.UpdateConsentRequest;
import com.itda.backend.dto.response.consent.ConsentPreviewResponse;
import com.itda.backend.dto.response.consent.ConsentResponse;
import com.itda.backend.dto.response.consent.PendingConsentRequestResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.GuardianChildQueryService;
import com.itda.backend.service.consent.ConsentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/children/{childId}")
@RequiredArgsConstructor
@Tag(name = "보호자 동의", description = "보호자의 기관별 정보 공유 동의와 대기 중 권한 요청")
public class ConsentController {

    private final ConsentService consentService;
    private final GuardianChildQueryService guardianChildQueryService;

    @GetMapping("/consent-preview")
    @Operation(summary = "동의 미리보기",
            description = "동의 화면에 그릴 아이·기관·공유 범위입니다. 저장하지 않습니다. "
                    + "동의할 때는 allowedFields 를 PUT /api/v1/children/{childId}/consent-scopes/{institutionId} 에 "
                    + "그대로 보냅니다. "
                    + "이미 이 기관에 동의가 있으면(다른 보호자의 동의 포함) 404 입니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "institutionId 누락·형식 오류 (INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "연결되지 않은 아이 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "삭제된 아이, 아이가 명부에 없는 기관, 이미 동의한 기관 (CONSENT_TARGET_NOT_FOUND)"
            )
    })
    public ApiResponse<ConsentPreviewResponse> preview(
            @AuthenticationPrincipal String userId, @PathVariable Long childId, @RequestParam Long institutionId) {
        return ApiResponse.success(consentService.preview(userId, childId, institutionId));
    }

    @PutMapping("/consent-scopes/{institutionId}")
    @Operation(summary = "동의 부여·철회",
            description = "grant 는 이 기관에 공유를 켭니다. 보호자 한 명이면 충분하고, 공유 항목은 기관 단위로 하나라 "
                    + "마지막으로 동의한 보호자의 항목이 그 기관 전체에 적용됩니다. "
                    + "revoke 는 이 기관에 대한 모든 보호자의 동의를 끕니다. 끌 동의가 없으면 아무것도 바꾸지 않고 200 입니다. "
                    + "철회하면 앞으로의 처리가 멈추고, 이미 열린 기관 열람 권한을 거둡니다 — 그 기관이 받은 인사이트와, "
                    + "그 기관 기록이 재료인 인사이트에 대해 다른 기관이 가진 권한입니다. grant 로 주간 인사이트 항목만 빼도 "
                    + "같은 권한을 거둡니다. 보호자 열람 권한과 이미 승인된 일지는 그대로 둡니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "반영 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "action 누락·모르는 값, grant 의 allowedFields 누락·빈 배열·모르는 항목·null 항목 "
                            + "(INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "연결되지 않은 아이 또는 CSRF 토큰 누락·불일치 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "삭제된 아이, 아이가 명부에 없는 기관 (CONSENT_TARGET_NOT_FOUND)"
            )
    })
    public ApiResponse<ConsentResponse> update(
            @AuthenticationPrincipal String userId,
            @PathVariable Long childId,
            @PathVariable Long institutionId,
            @Valid @RequestBody UpdateConsentRequest request) {
        return ApiResponse.success(
                consentService.update(userId, childId, institutionId, request.action(), request.allowedFields()));
    }

    @GetMapping("/institution-requests/pending")
    @Operation(summary = "대기 중 기관 권한 요청",
            description = "아이가 명부에 올라 있는데 아직 아무도 동의하지 않은 기관입니다. 기관 id 순입니다. "
                    + "보호자 화면의 알림 점·홈 배너·알림 화면이 이 목록을 봅니다. "
                    + "동의 상태는 아이 기준이라 다른 보호자가 동의한 기관은 빠집니다. "
                    + "동의했다가 꺼진 기관도 빠집니다 — 보호자가 직접 끈 것이라 다시 알리지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공. 대기 중인 요청이 없으면 빈 배열"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "연결되지 않았거나 삭제된 아이 (FORBIDDEN)"
            )
    })
    public ApiResponse<List<PendingConsentRequestResponse>> pendingRequests(
            @AuthenticationPrincipal String userId, @PathVariable Long childId) {
        return ApiResponse.success(guardianChildQueryService.pendingRequests(userId, childId));
    }
}
