package com.itda.backend.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.itda.backend.domain.record.RawRecord;
import com.itda.backend.dto.request.record.RawRecordUploadUrlRequest;
import com.itda.backend.dto.response.record.RawRecordResponse;
import com.itda.backend.dto.response.record.RawRecordUploadUrlResponse;
import com.itda.backend.dto.response.record.RecordProgressResponse;
import com.itda.backend.global.config.OpenApiConfig;
import com.itda.backend.global.exception.ErrorResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.record.RawRecordService;
import com.itda.backend.service.record.RawRecordUploadService;
import com.itda.backend.service.record.RecordProgressService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

// principal 은 JwtAuthenticationFilter 가 심은 내부 userId 다. 이 사용자의 소속 기관을 찾는
// 일은 RawRecordService 가 한다 — 컨트롤러는 저장소를 직접 보지 않는다. 클라이언트가 보낸
// institutionId는 여전히 신뢰하지 않는다.
// (이슈 #34 이전에는 여기 카카오 회원번호가 들어와 그대로 기관 식별자로 쓰이고 있었다.
//  즉 "기관"이 사실상 "카카오 계정 하나"였다.)
@RestController
@RequestMapping("/api/v1/raw-records")
@RequiredArgsConstructor
@Tag(name = "원본 기록", description = "인증된 기관의 원본 기록 업로드와 조회")
public class RawRecordController {

    private final RawRecordService rawRecordService;
    private final RecordProgressService recordProgressService;
    private final RawRecordUploadService rawRecordUploadService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "원본 기록 업로드",
            description = "인증 쿠키가 필요합니다. 요청 전에 받은 XSRF-TOKEN 쿠키 값을 "
                    + "X-XSRF-TOKEN 헤더에 포함해야 합니다. 화면이 저장소로 바로 올리는 업로드(upload-url → PUT → complete, "
                    + "#450)로 옮기면 없앱니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "업로드 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "파일 누락, 허용되지 않은 확장자·형식, 너무 긴 파일명 (RAW_RECORD_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요 또는 없는·탈퇴 회원 (UNAUTHORIZED, SESSION_USER_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰 누락, 가입 미완료 (SIGNUP_NOT_COMPLETED), 또는 기관 소속이 아닌 사용자",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "413",
                    description = "파일 크기 20MB 초과 (RAW_RECORD_FILE_TOO_LARGE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "원본 파일·기록 저장 실패 (RAW_RECORD_STORAGE_FAILED) 또는 가입 상태 조회 서버 오류 (INTERNAL_SERVER_ERROR)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<ApiResponse<RawRecordResponse>> upload(
            @AuthenticationPrincipal String userId,
            @RequestParam("file") MultipartFile file) {
        RawRecord saved = rawRecordService.ingest(userId, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(RawRecordResponse.from(saved)));
    }

    @PostMapping("/upload-url")
    @Operation(
            summary = "원본 기록 업로드 주소 받기 (1/3)",
            description = "파일 본문을 BE 를 거치지 않고 저장소(S3)로 바로 올리는 업로드의 첫 단계입니다 (#450). "
                    + "파일 이름 · 형식 · 크기를 한 번에 올리는 업로드와 같은 기준으로 확인하고, 접수 id 와 업로드 주소를 줍니다. "
                    + "브라우저는 uploadUrl 로 method(PUT) 요청을 보내며 headers 를 그대로 붙입니다. 주소에는 형식과 크기가 "
                    + "서명되어 다른 형식 · 크기로는 올라가지 않습니다. 운영 주소는 S3 이므로 쿠키 · CSRF 헤더를 붙이지 않고, "
                    + "로컬(S3 없음)은 /api 로 시작하는 BE 주소라 다른 API 처럼 쿠키 · CSRF 헤더를 붙입니다. "
                    + "expiresAt(10분)이 지나면 주소를 다시 받습니다. 다 올렸으면 "
                    + "POST /api/v1/raw-records/uploads/{uploadId}/complete 를 부릅니다. "
                    + "일지 수 · 혼잡 · AI 처리 줄은 파일 내용을 봐야 알아 완료 단계에서 거절합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "접수 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "크기 누락 · 0, 허용되지 않은 확장자 · 형식, 너무 긴 파일명 (RAW_RECORD_INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요 또는 없는·탈퇴 회원 (UNAUTHORIZED, SESSION_USER_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰 누락, 가입 미완료 (SIGNUP_NOT_COMPLETED), 또는 기관 소속이 아닌 사용자",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "413",
                    description = "파일 크기 20MB 초과 (RAW_RECORD_FILE_TOO_LARGE)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "업로드 주소 만들기 실패 (RAW_RECORD_STORAGE_FAILED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ResponseEntity<ApiResponse<RawRecordUploadUrlResponse>> uploadUrl(
            @AuthenticationPrincipal String userId, @RequestBody RawRecordUploadUrlRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(rawRecordUploadService.issue(userId, request)));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    @Operation(
            summary = "원본 기록 업로드 완료 알리기 (3/3)",
            description = "업로드 주소로 파일을 다 올린 뒤 부릅니다 (#450). 저장소에 파일이 있고 크기가 접수와 같은지 확인한 뒤, "
                    + "한 번에 올리는 업로드와 같은 흐름(일지 나누기 → 상한 확인 → 저장)으로 원본 기록을 만듭니다. "
                    + "이후(매칭 → 검증 → 요약)는 같습니다. 응답은 한 번에 올리는 업로드의 응답과 같은 원본 기록입니다. "
                    + "여러 번 불러도 원본 기록은 하나만 생깁니다 — 이미 완료된 접수는 만든 기록을 그대로 돌려줍니다. "
                    + "409 면 파일을 올린 뒤 다시 부르고, 429 는 잠시 뒤 이 요청만 다시 부릅니다(파일을 다시 올리지 않습니다). "
                    + "400 RAW_RECORD_ENTRY_LIMIT_EXCEEDED · 크기 불일치는 파일과 접수를 지우므로 처음부터 다시 올려야 합니다."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "완료 (이미 완료된 접수도 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "저장소의 파일 크기가 접수와 다름 (RAW_RECORD_INVALID_REQUEST), "
                            + "일지가 너무 많거나 너무 긴 파일 (RAW_RECORD_ENTRY_LIMIT_EXCEEDED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요 또는 없는·탈퇴 회원 (UNAUTHORIZED, SESSION_USER_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "CSRF 토큰 누락, 가입 미완료 (SIGNUP_NOT_COMPLETED), 또는 기관 소속이 아닌 사용자",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 접수이거나 다른 기관의 접수 (RAW_RECORD_UPLOAD_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "저장소에 아직 파일이 없음 (RAW_RECORD_UPLOAD_NOT_READY)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "처리 중인 업로드가 많음 (RAW_RECORD_UPLOAD_BUSY), 기관의 AI 처리 줄이 꽉 참 (AGENT_QUEUE_FULL)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "저장소 확인 · 읽기 또는 기록 저장 실패 (RAW_RECORD_STORAGE_FAILED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<RawRecordResponse> complete(
            @AuthenticationPrincipal String userId, @PathVariable Long uploadId) {
        return ApiResponse.success(RawRecordResponse.from(rawRecordUploadService.complete(userId, uploadId)));
    }

    @GetMapping("/progress")
    @Operation(
            summary = "업로드한 파일의 처리 현황",
            description = "우리 기관이 올린 파일을 최근 업로드부터 반환합니다. 파일마다 그 파일에서 나온 일지가 "
                    + "지금 어느 단계에 있는지 함께 줍니다 — stageIndex 는 0 등록 · 1 매칭 · 2 검증 · 3 요약 · "
                    + "4 1차 검토이고, state 는 running(자동 진행 중) · waiting(사람 확인 대기) · "
                    + "failed(시스템 오류) · done(완료)입니다. 파일을 기록 단위로 나누기 전이면 entries 가 "
                    + "비어 있습니다(PDF 는 아직 쪼개지지 않습니다)."
    )
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED) 또는 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<RecordProgressResponse>> progress(@AuthenticationPrincipal String userId) {
        return ApiResponse.success(recordProgressService.getProgress(userId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "원본 기록 단건 조회")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요 또는 없는·탈퇴 회원 (UNAUTHORIZED, SESSION_USER_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED) 또는 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "없는 기록이거나 다른 기관의 기록 (RAW_RECORD_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "가입 상태 조회 서버 오류 (INTERNAL_SERVER_ERROR)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<RawRecordResponse> getOne(
            @AuthenticationPrincipal String userId, @PathVariable Long id) {
        return ApiResponse.success(RawRecordResponse.from(rawRecordService.getById(id, userId)));
    }

    @GetMapping
    @Operation(summary = "원본 기록 목록 조회")
    @SecurityRequirement(name = OpenApiConfig.COOKIE_AUTH_SCHEME)
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 필요 또는 없는·탈퇴 회원 (UNAUTHORIZED, SESSION_USER_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "가입 미완료 (SIGNUP_NOT_COMPLETED) 또는 기관 소속이 아닌 사용자 (ORGANIZATION_NOT_ASSIGNED)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "가입 상태 조회 서버 오류 (INTERNAL_SERVER_ERROR)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
    public ApiResponse<List<RawRecordResponse>> list(@AuthenticationPrincipal String userId) {
        List<RawRecordResponse> records = rawRecordService.getByInstitution(userId).stream()
                .map(RawRecordResponse::from)
                .toList();
        return ApiResponse.success(records);
    }
}
