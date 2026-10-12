package com.itda.backend.service;

import java.security.SecureRandom;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.itda.backend.domain.Invitation;

/**
 * 초대 코드를 만들고, 보호자가 입력한 값을 저장 형식으로 맞춘다.
 *
 * <p>카톡으로 보고 옮겨 적다 헷갈리는 0·O·1·I·L 을 뺀 31자에서 8자리를 뽑는다(31⁸ ≈ 8,500억).
 * 입력 시도 횟수를 제한하지 않기로 해서, 무차별 대입을 길이와 10분 만료·일회용으로 버틴다.
 * 예측할 수 없어야 하므로 {@link SecureRandom} 을 쓴다.
 *
 * <p>테스트가 정해진 코드를 넣을 수 있도록 빈으로 둔다.
 */
@Component
public class InviteCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(Invitation.CODE_LENGTH);
        for (int i = 0; i < Invitation.CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * 공백과 하이픈을 지우고 대문자로 바꾼다. 화면에는 {@code XXXX-XXXX} 로 보이고 사람이 소문자로 칠 수도 있다.
     * 길이나 문자가 형식에 맞지 않으면 {@code null} — 그런 코드는 저장돼 있을 수 없다.
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String code = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != Invitation.CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }
}
