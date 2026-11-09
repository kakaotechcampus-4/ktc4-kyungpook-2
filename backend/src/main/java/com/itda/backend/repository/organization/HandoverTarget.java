package com.itda.backend.repository.organization;

/**
 * 인계 코드로 찾은 대상의 id 만. 잠그기 전에 읽으므로 엔티티로 가져오지 않는다 — 같은 영속성 컨텍스트에 잠그지 않은 엔티티가
 * 먼저 올라가 있으면, 뒤에서 잠그며 다시 읽어도 Hibernate 가 옛 상태를 그대로 돌려줄 수 있다 ({@code InvitationTarget} 과 같다).
 */
public record HandoverTarget(Long id, Long organizationId, Long issuedBy) {
}
