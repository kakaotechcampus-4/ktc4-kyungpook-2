package com.itda.backend.dto.request.child;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 돌봄 정보 저장 (api-spec G-13). 통째로 덮어쓴다 — 부분 수정이 아니다.
 *
 * <p>앞뒤 공백을 생성자에서 떼고 그 뒤에 검증한다({@link RegisterChildRequest} 와 같은 방식). 안쪽 공백은 그대로 둔다
 * ("갑각류 (새우)"). 공백뿐인 값은 {@code @NotBlank} 에서 걸린다. 실패는 전부 400 INVALID_REQUEST 다.
 *
 * <p>알레르기는 같은 값을 하나로 합친다(처음 순서 유지). 그래서 20개는 <b>합친 뒤의 개수</b>다 — 같은 값이 섞여 21개를
 * 보내도 합쳐서 20개 이하면 통과한다. 복용약은 합치지 않는다.
 */
public record UpdateCareInfoRequest(
        @Schema(example = "[\"우유와 유제품\", \"땅콩\"]")
        @NotNull
        @Size(max = 20)
        List<@NotBlank @Size(max = 30) String> allergies,

        @NotNull
        @Size(max = 20)
        List<@NotNull @Valid MedicationRequest> medications
) {

    public UpdateCareInfoRequest {
        // null 원소는 null 로 둬 @NotBlank 가 잡게 한다
        allergies = (allergies == null) ? null
                : allergies.stream().map(a -> a == null ? null : a.strip()).distinct().toList();
    }

    /**
     * 복용약 하나.
     *
     * @param time 복용 시간 문구. 빠지거나 null 이면 빈 문자열로 받는다 — 그래서 {@code @NotNull} 을 두지 않는다
     *             (두면 OpenAPI 에 필수로 표시된다)
     */
    public record MedicationRequest(
            @Schema(example = "○○정")
            @NotBlank
            @Size(max = 30)
            String name,

            @Schema(example = "점심 식후")
            @Size(max = 30)
            String time
    ) {

        public MedicationRequest {
            name = (name == null) ? null : name.strip();
            time = (time == null) ? "" : time.strip();
        }
    }
}
