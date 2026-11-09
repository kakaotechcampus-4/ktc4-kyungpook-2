package com.itda.backend.service.user;

/** 카카오에 물어 본 지금 연결 상태. */
public enum KakaoLinkStatus {

    /** 앱과 연결돼 있다(200). 연결 해제 웹훅이 왔어도 그 뒤 다시 연결된 것이다. */
    LINKED,

    /** 앱과 연결돼 있지 않다(400 {@code -101}). */
    NOT_LINKED,

    /** 알 수 없다(타임아웃 · 5xx · 그 밖의 오류). 나중에 다시 묻는다. */
    UNKNOWN
}
