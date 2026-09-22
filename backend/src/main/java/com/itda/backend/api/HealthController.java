package com.itda.backend.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "상태 확인")
public class HealthController {

    @GetMapping("/api/health")
    @Operation(summary = "상태 확인")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
