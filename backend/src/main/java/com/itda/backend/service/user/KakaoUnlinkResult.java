package com.itda.backend.service.user;

/** 카카오 연결 해제 한 번의 결과. */
public enum KakaoUnlinkResult {

    /** 연결이 끊겼다. 이미 끊긴 사용자(400 {@code -101})도 목표 상태라 여기다. */
    UNLINKED,

    /** 카카오가 분명히 거절했다(키 오류, 설정 미완료, 파라미터 오류 등). 이번 호출로는 끊기지 않았다. */
    FAILED,

    /** 끊겼는지 모른다(타임아웃, 연결 끊김, 5xx, 일시 오류 코드). */
    UNKNOWN
}
