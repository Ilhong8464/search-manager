package com.cp.oslo.util;

import java.util.regex.Pattern;

public class CaseUtils {

    private static final Pattern UNDERSCORE_PATTERN = Pattern.compile("_([a-z])");

    /**
     * 스네이크 케이스(snake_case) 또는 대문자 스네이크 케이스(SNAKE_CASE)를
     * 카멜 케이스(camelCase)로 변환합니다.
     */
    public static String toCamelCase(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }

        // 1. 모두 대문자인 경우 소문자로 변환 후 처리 (예: USER_ID -> user_id)
        if (s.equals(s.toUpperCase())) {
            s = s.toLowerCase();
        }

        // 2. _로 시작하는 경우 제외 (예: _score)
        if (s.startsWith("_")) {
            return s;
        }

        // 3. 변환 로직
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;

        for (int i = 0; i < s.length(); i++) {
            char currentChar = s.charAt(i);
            if (currentChar == '_') {
                nextUpper = true;
            } else {
                if (nextUpper) {
                    result.append(Character.toUpperCase(currentChar));
                    nextUpper = false;
                } else {
                    result.append(currentChar);
                }
            }
        }

        return result.toString();
    }
}
