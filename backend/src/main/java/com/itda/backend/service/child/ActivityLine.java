package com.itda.backend.service.child;

import java.time.LocalDateTime;

import com.itda.backend.dto.response.child.ParentActivityResponse;

public record ActivityLine(LocalDateTime at, ParentActivityResponse line) {
}
