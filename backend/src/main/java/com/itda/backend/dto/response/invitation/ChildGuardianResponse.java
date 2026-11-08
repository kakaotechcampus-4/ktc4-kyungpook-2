package com.itda.backend.dto.response.invitation;

import java.time.OffsetDateTime;

/**
 * O-18 아이와 연결된 보호자 한 명. {@code linkedByThisOrganization} 이 참인 보호자만 이 기관이 해제할 수 있다.
 * 보호자 이름은 저장하지 않는다 — 화면이 "아이 이름 + 보호자" 로 쓴다.
 *
 * <p>같은 아이의 보호자가 여럿이면 이름 없이는 누가 누군지 모른다. 연결 해제할 사람을 잘못 고르지 않게, 우리 기관 코드로
 * 연결된 보호자는 그때 쓴 초대 코드를 준다 — 기관이 어떤 코드를 누구에게 줬는지로 대조한다. 연결 시각도 분까지 보인다.
 *
 * @param invitationCode 이 보호자가 연결할 때 쓴 우리 기관의 초대 코드. 다른 기관 코드로 연결됐으면 {@code null} —
 *                       남의 기관 코드는 보여 주지 않는다(그 보호자는 우리가 해제할 수도 없다)
 */
public record ChildGuardianResponse(
        String userId, OffsetDateTime linkedAt, boolean linkedByThisOrganization, String invitationCode) {
}
