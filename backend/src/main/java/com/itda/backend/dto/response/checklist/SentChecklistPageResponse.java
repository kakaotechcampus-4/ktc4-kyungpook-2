package com.itda.backend.dto.response.checklist;

import java.util.List;

/**
 * O-71 보낸 목록 한 쪽.
 *
 * @param nextBefore 더 오래된 요청이 있으면 다음 쪽을 부를 때 {@code before} 로 넣을 id. 없으면 {@code null}
 */
public record SentChecklistPageResponse(List<SentChecklistResponse> checklists, String nextBefore) {
}
