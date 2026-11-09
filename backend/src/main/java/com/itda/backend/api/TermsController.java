package com.itda.backend.api;

import java.util.List;

import com.itda.backend.domain.user.UserRole;
import com.itda.backend.dto.response.user.TermsDocumentResponse;
import com.itda.backend.global.response.ApiResponse;
import com.itda.backend.service.user.TermsDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@Tag(name = "인증", description = "카카오 로그인 이후의 세션 조회·회원가입·약관 동의·로그아웃")
public class TermsController {

    private final TermsDocumentService termsDocumentService;

    @GetMapping("/api/v1/terms")
    @Operation(
            summary = "약관 본문",
            description = "그 역할이 동의해야 하는 약관을 지금 판의 본문까지 모두 줍니다. 로그인 없이 부를 수 있습니다. "
                    + "동의 화면은 열릴 때 이것을 받아 두었다가, 동의할 때 체크한 약관과 각 version 을 "
                    + "POST /api/v1/auth/terms 의 versions 로 보냅니다. draft 가 true 면 팀 검토 전 초안입니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "role 누락 또는 parent · org 가 아님 (INVALID_REQUEST)"
            )
    })
    public ResponseEntity<ApiResponse<List<TermsDocumentResponse>>> terms(
            @Parameter(description = "역할", schema = @Schema(allowableValues = {"parent", "org"}))
            @RequestParam @Pattern(regexp = "parent|org") String role) {
        UserRole userRole = "org".equals(role) ? UserRole.ORGANIZATION : UserRole.PARENT;
        List<TermsDocumentResponse> body = termsDocumentService.forRole(userRole).stream()
                .map(TermsDocumentResponse::from)
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(ApiResponse.success(body));
    }
}
