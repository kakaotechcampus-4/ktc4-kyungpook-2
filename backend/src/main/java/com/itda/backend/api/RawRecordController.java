package com.itda.backend.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.itda.backend.domain.RawRecord;
import com.itda.backend.dto.RawRecordResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.RawRecordService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// ponytail: 아직 Member/Institution 도메인이 없어서, 인증된 카카오ID(JwtAuthenticationFilter가
// SecurityContext principal로 심어둔 subject)를 그대로 기관 식별자로 쓴다. 클라이언트가 보낸
// institutionId는 더 이상 신뢰하지 않음 — 인증된 사용자만 자기 소속(=자기 kakaoId) 기록에 접근 가능.
// Member→Institution 매핑이 생기면 여기서 institutionId 대신 실제 소속 기관을 조회해서 넘길 것.
@RestController
@RequestMapping("/api/v1/raw-records")
@RequiredArgsConstructor
@Tag(name = "원본 기록", description = "인증된 기관의 원본 기록 업로드와 조회")
public class RawRecordController {

    private final RawRecordService rawRecordService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "원본 기록 업로드",
            description = "인증 쿠키가 필요합니다. 요청 전에 받은 XSRF-TOKEN 쿠키 값을 "
                    + "X-XSRF-TOKEN 헤더에 포함해야 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "업로드 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰 누락 또는 권한 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<ApiResponse<RawRecordResponse>> upload(
            @AuthenticationPrincipal String institutionId,
            @RequestParam("file") MultipartFile file) {
        RawRecord saved = rawRecordService.ingest(institutionId, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(RawRecordResponse.from(saved)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "원본 기록 단건 조회")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<RawRecordResponse> getOne(
            @AuthenticationPrincipal String institutionId, @PathVariable Long id) {
        return ApiResponse.success(RawRecordResponse.from(rawRecordService.getById(id, institutionId)));
    }

    @GetMapping
    @Operation(summary = "원본 기록 목록 조회")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<RawRecordResponse>> list(@AuthenticationPrincipal String institutionId) {
        List<RawRecordResponse> records = rawRecordService.getByInstitution(institutionId).stream()
                .map(RawRecordResponse::from)
                .toList();
        return ApiResponse.success(records);
    }
}
