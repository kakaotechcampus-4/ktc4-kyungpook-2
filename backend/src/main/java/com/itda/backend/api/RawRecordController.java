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
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.RawRecordService;

import lombok.RequiredArgsConstructor;

// ponytail: 아직 Member/Institution 도메인이 없어서, 인증된 카카오ID(JwtAuthenticationFilter가
// SecurityContext principal로 심어둔 subject)를 그대로 기관 식별자로 쓴다. 클라이언트가 보낸
// institutionId는 더 이상 신뢰하지 않음 — 인증된 사용자만 자기 소속(=자기 kakaoId) 기록에 접근 가능.
// Member→Institution 매핑이 생기면 여기서 institutionId 대신 실제 소속 기관을 조회해서 넘길 것.
@RestController
@RequestMapping("/api/v1/raw-records")
@RequiredArgsConstructor
public class RawRecordController {

    private final RawRecordService rawRecordService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<RawRecordResponse>> upload(
            @AuthenticationPrincipal String institutionId,
            @RequestParam("file") MultipartFile file) {
        RawRecord saved = rawRecordService.ingest(institutionId, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(RawRecordResponse.from(saved)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RawRecordResponse> getOne(
            @AuthenticationPrincipal String institutionId, @PathVariable Long id) {
        return ApiResponse.success(RawRecordResponse.from(rawRecordService.getById(id, institutionId)));
    }

    @GetMapping
    public ApiResponse<List<RawRecordResponse>> list(@AuthenticationPrincipal String institutionId) {
        List<RawRecordResponse> records = rawRecordService.getByInstitution(institutionId).stream()
                .map(RawRecordResponse::from)
                .toList();
        return ApiResponse.success(records);
    }
}
