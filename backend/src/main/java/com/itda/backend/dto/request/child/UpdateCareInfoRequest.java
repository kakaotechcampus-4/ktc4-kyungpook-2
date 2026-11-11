package com.itda.backend.dto.request.child;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

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
        allergies = (allergies == null) ? null
                : allergies.stream().map(a -> a == null ? null : a.strip()).distinct().toList();
    }

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
