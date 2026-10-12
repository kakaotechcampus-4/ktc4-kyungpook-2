package com.itda.backend.dto.request;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/institutions/me/children 의 요청. 기관 담당자가 아이를 등록한다.
 *
 * <p>{@code name} 은 앞뒤 공백을 떼고 검증한다. 검증은 생성 뒤에 돌기 때문에 공백만 있는 이름은
 * {@code @NotBlank} 에서, 길이는 뗀 값 기준으로 {@code @Size} 에서 걸린다.
 *
 * <p>{@code birthDate} 를 {@code LocalDate} 가 아니라 문자열로 받는다. Jackson 기본 날짜 모듈은
 * "2017-03-14T00:00:00" 이나 [2017, 3, 14] 도 LocalDate 로 바꿔주고, {@code @JsonFormat} 패턴을 써도
 * 배열은 통과하며 "2017-02-30" 을 2017-02-28 로 고쳐 받는다. "yyyy-MM-dd" 만 받으려고 문자열 형식과
 * 실제 달력 날짜를 {@link #isBirthDateValid()} 로 따로 본다. 실패는 전부 400 INVALID_REQUEST 다.
 *
 * <p>{@code externalId} 는 기관이 쓰는 관리번호로 선택이다. 앞뒤 공백을 떼고, 남는 게 없으면 "번호 없음"(null) 이다.
 * 같은 기관 안 중복은 서비스와 DB 유니크 제약이 409 로 막는다.
 */
public record RegisterChildRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @Schema(type = "string", format = "date", example = "2017-03-14")
        @NotNull
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
        String birthDate,

        @Schema(example = "2026-0031")
        @Size(max = 50)
        String externalId
) {

    public RegisterChildRequest {
        name = (name == null) ? null : name.strip();
        externalId = (externalId == null || externalId.isBlank()) ? null : externalId.strip();
    }

    /**
     * 실제로 있는 날짜이고 오늘 이후가 아니어야 한다. null 은 {@code @NotNull} 이 따로 잡는다.
     *
     * <p>예외가 검증기 밖으로 새면 400 이 아니라 500 이 되므로 실패는 false 로만 알린다. is 로 시작해 getter
     * 처럼 보이므로 {@code @JsonIgnore} 가 없으면 Swagger 요청 스키마에 birthDateValid 라는 없는 필드로 나타난다.
     */
    @JsonIgnore
    @AssertTrue
    public boolean isBirthDateValid() {
        if (birthDate == null) {
            return true;
        }
        try {
            return !LocalDate.parse(birthDate).isAfter(LocalDate.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 검증을 통과한 뒤에만 부른다. */
    public LocalDate birthDateValue() {
        return LocalDate.parse(birthDate);
    }
}
