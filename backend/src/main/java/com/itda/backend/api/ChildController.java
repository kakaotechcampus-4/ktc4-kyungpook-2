package com.itda.backend.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.itda.backend.dto.request.child.RegisterChildRequest;
import com.itda.backend.dto.response.child.ChildRosterResponse;
import com.itda.backend.dto.response.child.RegisteredChildResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.child.ChildService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/institutions/me/children")
@RequiredArgsConstructor
@Tag(name = "담당 아동", description = "기관 소속 아동 명부")
public class ChildController {

    private final ChildService childService;

    @GetMapping
    @Operation(summary = "담당 아동 목록", description = "인증된 기관 소속 아동만 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)"
            )
    })
    public ApiResponse<List<ChildRosterResponse>> getRoster(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(childService.getRoster(userId));
    }

    @PostMapping
    @Operation(summary = "아이 등록",
            description = "인증된 기관에 아이를 등록합니다. 등록 직후 상태는 pending_consent 입니다. "
                    + "birthDate 는 yyyy-MM-dd 문자열만 받습니다. externalId(기관 관리번호)는 필수이며 "
                    + "같은 기관 안에서는 겹칠 수 없습니다. 보호자에게 보낼 초대 코드(10분, 일회용)를 함께 발급합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "이름 누락·100자 초과, 생년월일 형식 오류·없는 날짜·미래 날짜, "
                            + "관리번호 누락·공백·50자 초과 (INVALID_REQUEST)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 "
                            + "(ORGANIZATION_NOT_ASSIGNED), CSRF 토큰 누락·불일치 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "같은 기관에서 이미 쓰고 있는 관리번호 (DUPLICATE_EXTERNAL_ID) · 이름·생년월일이 같은 아이가 이미 명부에 있음 (DUPLICATE_CHILD)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "동시에 발급된 초대 코드와 겹침. 아이도 등록되지 않음. 다시 요청 (INVITE_CODE_ISSUE_FAILED)"
            )
    })
    public ResponseEntity<ApiResponse<RegisteredChildResponse>> register(
            @AuthenticationPrincipal String userId, @Valid @RequestBody RegisterChildRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(childService.register(userId, request)));
    }

    @DeleteMapping("/{childId}")
    @Operation(summary = "명부에서 빼기",
            description = "아이를 우리 기관 명부에서 뺍니다. 아이 자체와 보호자 연결은 그대로입니다. "
                    + "우리 기관에 대한 공유 동의가 꺼지고(ROSTER_REMOVED), 우리 기관이 받았거나 재료를 낸 인사이트의 "
                    + "기관 열람 권한을 거두며, 아직 쓸 수 있는 초대 코드를 취소합니다. 관리번호는 비워져 다시 쓸 수 있습니다. "
                    + "이미 AI 가 처리 중인 일은 멈추지 않을 수 있습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "뺐음. 본문 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED), 기관 소속이 아닌 사용자 "
                            + "(ORGANIZATION_NOT_ASSIGNED), CSRF 토큰 누락·불일치 (FORBIDDEN)"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없거나 삭제됐거나 우리 기관 명부에 없는 아이. 이미 뺀 아이도 같음 (CHILD_NOT_FOUND)"
            )
    })
    public ResponseEntity<Void> removeFromRoster(@AuthenticationPrincipal String userId, @PathVariable Long childId) {
        childService.removeFromRoster(userId, childId);
        return ResponseEntity.noContent().build();
    }
}
