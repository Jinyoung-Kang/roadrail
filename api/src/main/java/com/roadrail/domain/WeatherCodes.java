package com.roadrail.domain;

/** 기상청 코드 · 값 해석 — 길 날씨 · 판단 카드 날씨 · 날씨 표가 함께 쓰는 순수 규칙 (서비스끼리 빌려 쓰던 정적 도우미를 모음) */
public final class WeatherCodes {
    private WeatherCodes() {}

    /** 기상청 숫자 값(TMP · POP · T1H …) — 비었거나 숫자가 아니면 null (짐작하지 않음) */
    public static Integer parseInt(String s) {
        try { return s == null ? null : Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** 기상청 PTY 코드 → 이름 (0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기) */
    public static String ptyName(String code) {
        if (code == null) return null;
        return switch (code.trim()) {
            case "0" -> "없음";
            case "1" -> "비";
            case "2" -> "비/눈";
            case "3" -> "눈";
            case "4" -> "소나기";
            case "5" -> "빗방울";           // 5 · 6 · 7 은 초단기예보 · 실황에만
            case "6" -> "빗방울눈날림";
            case "7" -> "눈날림";
            default -> code;
        };
    }

    /** 기상청 SKY 코드 → 이름 */
    public static String skyName(String code) {
        if (code == null) return null;
        return switch (code.trim()) {
            case "1" -> "맑음";
            case "3" -> "구름많음";
            case "4" -> "흐림";
            default -> code;
        };
    }
}
