package com.itda.backend.service.invitation;

import java.security.SecureRandom;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.itda.backend.domain.invitation.Invitation;

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
