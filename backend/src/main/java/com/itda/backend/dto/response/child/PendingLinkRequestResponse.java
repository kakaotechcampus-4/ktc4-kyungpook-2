package com.itda.backend.dto.response.child;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 기관이 보는 연결 요청 하나. 같은 아이에 보호자 여럿이 요청했으면 한 항목으로 묶는다 — 기관은 아이 단위로 수락·거절한다.
 *
 * @param requesters  요청한 보호자들. 먼저 요청한 순서다
 * @param requestedAt 가장 먼저 요청한 시각
 */
public record PendingLinkRequestResponse(
        RequestedChild child, List<Requester> requesters, OffsetDateTime requestedAt) {

    /** 수락 전이라 관리번호는 없다. 기관이 수락하면서 넣는다. */
    public record RequestedChild(String id, String name, String birthDate) {
    }

    /** @param name 카카오 닉네임. 사용자가 제공에 동의하지 않았으면 null */
    public record Requester(String name) {
    }
}
