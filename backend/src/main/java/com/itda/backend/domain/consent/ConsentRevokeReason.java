package com.itda.backend.domain.consent;

/** 동의가 왜 꺼졌는지. 누가 껐는지는 {@code revoked_by} 에 따로 남는다 (DB 스키마 §5.5). */
public enum ConsentRevokeReason {

    /** 보호자가 "공유 중단"을 눌렀다 (G-41 revoke). 그 기관의 모든 보호자 동의가 함께 꺼진다. */
    GUARDIAN_REVOKED,

    /** 보호자 연결이 끊겼다 (G-05 본인, O-19 기관). 끊긴 그 보호자의 동의만 꺼진다. */
    GUARDIAN_UNLINKED,

    /** 아이가 기관 명부에서 빠졌다. 지금은 테스트 아이 스크립트만 쓴다. {@code revoked_by} 는 비어 있다. */
    ROSTER_REMOVED
}
