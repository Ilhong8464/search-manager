package com.cp.oslo.util;

import org.springframework.stereotype.Component;

/**
 * 한글 자소 분리 및 초성 추출 유틸리티
 * 유니코드 한글 분해 로직을 구현합니다.
 */
@Component
public class HangulJamoUtils {

    // 한글 유니코드 시작/끝
    private static final char HANGUL_START = 0xAC00;
    private static final char HANGUL_END = 0xD7A3;

    // 초성 (19개)
    private static final char[] CHO = {
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    // 중성 (21개)
    private static final char[] JUNG = {
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 
        'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    };

    // 종성 (28개 - 0번째는 종성 없음)
    private static final char[] JONG = {
        '\0', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 
        'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    /**
     * 문자열을 자소 단위로 분리합니다. (예: "강남" -> "ㄱㅏㅇㄴㅏㅁ")
     */
    public String decompose(String text) {
        if (text == null) return null;

        StringBuilder sb = new StringBuilder();

        for (char ch : text.toCharArray()) {
            if (ch >= HANGUL_START && ch <= HANGUL_END) {
                // 한글인 경우 자소 분리
                int uniBase = ch - HANGUL_START;

                int choIdx = uniBase / (21 * 28);
                int jungIdx = (uniBase % (21 * 28)) / 28;
                int jongIdx = (uniBase % (21 * 28)) % 28;

                sb.append(CHO[choIdx]);
                sb.append(JUNG[jungIdx]);
                
                // 종성이 있는 경우에만 추가
                if (jongIdx > 0) {
                    sb.append(JONG[jongIdx]);
                }
            } else {
                // 한글이 아닌 경우 그대로 추가
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 문자열에서 초성만 추출합니다. (예: "강남" -> "ㄱㄴ")
     */
    public String extractChosung(String text) {
        if (text == null) return null;

        StringBuilder sb = new StringBuilder();

        for (char ch : text.toCharArray()) {
            if (ch >= HANGUL_START && ch <= HANGUL_END) {
                int uniBase = ch - HANGUL_START;
                int choIdx = uniBase / (21 * 28);
                sb.append(CHO[choIdx]);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * 주어진 문자가 초성 범위에 있는지 확인합니다.
     * (자동완성 검색 시 입력값이 초성만으로 구성되었는지 판단 용도)
     */
    public boolean isChosung(char ch) {
        for (char c : CHO) {
            if (c == ch) return true;
        }
        return false;
    }
    
    /**
     * 문자열이 초성으로만 이루어져 있는지 확인합니다.
     */
    public boolean isAllChosung(String text) {
        if (text == null || text.isEmpty()) return false;
        
        for (char ch : text.toCharArray()) {
            // 공백은 무시하거나 초성으로 취급하지 않을 수 있음. 여기서는 공백도 허용(또는 건너뜀)할지 결정해야 함.
            // 보통 검색어에 공백이 섞일 수 있으므로, 공백은 건너뛰고 나머지 문자가 모두 초성인지 확인
            if (Character.isWhitespace(ch)) continue;
            if (!isChosung(ch)) return false;
        }
        return true;
    }
}
