package com.itda.backend.repository;

/**
 * 초대 코드가 가리키는 행들의 id. 수락은 이것으로 어느 행을 어떤 순서로 잠글지 정한다.
 *
 * <p>엔티티 대신 id 만 받는 이유: 잠그기 전에 {@code Invitation} 엔티티를 영속성 컨텍스트에 올려 두면,
 * 뒤의 잠금 조회가 DB 의 최신 상태 대신 이미 올라온 예전 엔티티를 그대로 돌려줄 수 있다.
 */
public record InvitationTarget(Long id, Long childId, Long organizationId) {
}
