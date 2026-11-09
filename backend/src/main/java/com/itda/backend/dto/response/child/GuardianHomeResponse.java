package com.itda.backend.dto.response.child;

import java.util.List;

public record GuardianHomeResponse(GuardianChildResponse child, List<ParentActivityResponse> activity) {
}
