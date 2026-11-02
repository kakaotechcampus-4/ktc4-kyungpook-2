package com.itda.backend.api;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.child.UpdateCareInfoRequest;
import com.itda.backend.dto.response.child.ChildCareInfoResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.ChildCareInfoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// docs/api/api-spec.md G-13(돌봄 정보 저장). 조회는 보호자 아이 목록·홈(G-10·G-11)의 care 가 맡는다.
@RestController
@RequestMapping("/api/v1/children/{childId}")
@RequiredArgsConstructor
@Tag(name = "보호자 돌봄 정보", description = "보호자가 적어 두는 아이의 알레르기와 복용약. 기관에는 공유하지 않습니다")
public class ChildCareInfoController {

    private final ChildCareInfoService childCareInfoService;

    @PutMapping("/care-info")
    @Operation(summary = "돌봄 정보 저장",
            description = "알레르기와 복용약을 통째로 덮어씁니다(부분 수정 아님). 현재 이 아이와 연결된 모든 보호자가 조회·수정할 수 "
                    + "있고 나중에 저장한 쪽이 이깁니다. 기관에는 공유하지 않습니다. 앞뒤 공백은 떼고, 같은 알레르기는 하나로 "
                    + "합칩니다. 각 목록은 20개(알레르기는 합친 뒤 개수), 글자는 30자까지입니다. 복용 시간은 생략할 수 있습니다.")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "목록 누락·20개 초과, 빈 값·30자 초과, 복용약 이름 없음 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 보호자가 아닌 사용자 (PARENT_REQUIRED), "
                            + "이 보호자와 연결되지 않았거나 삭제된 아이·CSRF 토큰 누락 (FORBIDDEN)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<ChildCareInfoResponse> save(
            @AuthenticationPrincipal String userId,
            @PathVariable Long childId,
            @Valid @RequestBody UpdateCareInfoRequest request) {
        return ApiResponse.success(childCareInfoService.save(userId, childId, request));
    }
}
