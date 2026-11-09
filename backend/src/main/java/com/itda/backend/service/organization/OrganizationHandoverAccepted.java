package com.itda.backend.service.organization;

/**
 * 후임이 인계 코드로 가입해, 발급한 담당자의 탈퇴를 접수했다 (#478). 커밋 뒤 탈퇴 실행기를 깨운다.
 *
 * <p>인계 서비스가 탈퇴 실행기를 직접 부르지 않는 이유: 실행기 → 탈퇴 단계 → 보호자 연결 서비스 → 회원 서비스 → 인계 서비스로
 * 빈이 돌고 돈다. 이벤트를 놓쳐도 탈퇴 주기 작업이 1분 안에 다시 세운다.
 *
 * @param userId 탈퇴를 접수한 이전 담당자
 */
public record OrganizationHandoverAccepted(long userId) {
}
