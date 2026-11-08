package com.itda.backend.dto.response.child;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 기관이 보는 연결 요청 하나. 같은 아이에 보호자 여럿이 요청했으면 한 항목으로 묶는다 — 기관은 아이 단위로 수락·거절한다.
 *
 * @param requesterCount 요청한 보호자 수. 보호자 이름은 저장하지 않아 수만 준다 — 화면은 "아이 이름 + 보호자" 로 쓴다
 * @param requestedAt 가장 먼저 요청한 시각
 */
public record PendingLinkRequestResponse(
        RequestedChild child, int requesterCount, OffsetDateTime requestedAt) {

    /** 수락 전이라 관리번호는 없다. 기관이 수락하면서 넣는다. */
    public record RequestedChild(String id, String name, String birthDate) {
    }
}
