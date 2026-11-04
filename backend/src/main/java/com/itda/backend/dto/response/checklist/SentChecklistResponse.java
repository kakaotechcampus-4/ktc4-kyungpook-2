package com.itda.backend.dto.response.checklist;

import java.time.LocalDateTime;
import java.util.List;

/**
 * O-71 기관이 보낸 준비물·확인사항 한 건.
 *
 * @param total     지금 명부에 있는 받은 아이 수. 명부에서 빠진 아이는 세지 않는다
 * @param confirmed 그중 확인된 아이 수
 * @param targets   받은 아이 전부(명부에서 빠진 아이도), 아이 id 순
 */
public record SentChecklistResponse(
        String id,
        List<String> items,
        LocalDateTime sentAt,
        int total,
        int confirmed,
        List<Target> targets) {

    /**
     * @param onRoster        아이가 삭제되지 않았고 지금도 우리 기관 명부에 있다
     * @param confirmedAt     확인한 시각. 아직이면 {@code null}
     * @param confirmedByName 확인한 보호자의 카카오 닉네임. 아직이거나 닉네임이 없으면 {@code null}
     */
    public record Target(ChildRef child, boolean onRoster, LocalDateTime confirmedAt, String confirmedByName) {
    }

    public record ChildRef(String id, String name) {
    }
}
