package com.itda.backend.dto.response.checklist;

import java.util.List;

public record SentChecklistPageResponse(List<SentChecklistResponse> checklists, String nextBefore) {
}
