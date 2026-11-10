package com.itda.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.checklist.SendChecklistRequest;
import com.itda.backend.dto.response.checklist.SentChecklistPageResponse;
import com.itda.backend.dto.response.checklist.SentChecklistResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.checklist.ChecklistService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/institutions/me/checklists")
@RequiredArgsConstructor
@Tag(name = "준비물·확인사항", description = "기관이 보호자에게 보내는 준비물 · 확인사항과 확인 결과")
public class ChecklistController {

    private final ChecklistService checklistService;

    @PostMapping
    @Operation(summary = "준비물·확인사항 보내기",
            description = "우리 기관 명부의 아이들에게 항목 1~10줄(줄마다 100자)을 한 번에 보냅니다. 받는 아이는 보낸 순간의 명부로 "
                    + "정해지고, 공유 동의와는 상관없습니다. 보낸 뒤에는 고칠 수 없고 내리기만 할 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "보냄"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "항목 0개·11개 이상·101자 이상·빈 줄, 아이 0명 (INVALID_REQUEST), "
                            + "명부에 없는 아이가 섞임 (INVALID_CHECKLIST_TARGET)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아님 (ORGANIZATION_NOT_ASSIGNED), CSRF 토큰 누락 (FORBIDDEN)"
            )
    })
    public ResponseEntity<ApiResponse<SentChecklistResponse>> send(
            @AuthenticationPrincipal String userId, @Valid @RequestBody SendChecklistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(checklistService.send(userId, request.items(), request.childIds())));
    }

    @GetMapping
    @Operation(summary = "보낸 준비물·확인사항 목록",
            description = "내리지 않은 요청을 최신순 50개씩 보여 줍니다. 요청마다 받은 아이별 확인 여부와 확인한 보호자를 싣습니다. "
                    + "명부에서 빠진 아이는 onRoster=false 이고 total · confirmed 에서 빠집니다. nextBefore 가 있으면 "
                    + "그 값을 before 로 넣어 더 오래된 요청을 이어 봅니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "before 가 우리 기관 요청이 아님 (INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아님 (ORGANIZATION_NOT_ASSIGNED)"
            )
    })
    public ApiResponse<SentChecklistPageResponse> list(
            @AuthenticationPrincipal String userId, @RequestParam(required = false) Long before) {
        return ApiResponse.success(checklistService.list(userId, before));
    }

    @DeleteMapping("/{checklistId}")
    @Operation(summary = "준비물·확인사항 내리기",
            description = "받은 아이 모두에게서 통째로 내립니다. 이미 내렸으면 아무것도 바꾸지 않고 같은 204 입니다. "
                    + "이미 남은 확인 기록은 지우지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "내림. 본문 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아님 (ORGANIZATION_NOT_ASSIGNED), CSRF 토큰 누락 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없거나 다른 기관 요청 (CHECKLIST_NOT_FOUND)"
            )
    })
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal String userId, @PathVariable Long checklistId) {
        checklistService.cancel(userId, checklistId);
        return ResponseEntity.noContent().build();
    }
}
