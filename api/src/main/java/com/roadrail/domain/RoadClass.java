package com.roadrail.domain;

import java.util.regex.Pattern;

/**
 * 카카오 경로의 도로 이름 → 도로 종류. 카카오 응답에는 도로 등급 필드가 없어 **이름으로만** 분류한다.
 * 고속도로 = '고속도로'가 들어간 이름 · 도시고속 = 주요 자동차전용도로(올림픽대로 등) · 국도 = '국도' 또는 'N번국도'
 * · 그 밖(대로·로·길 …)은 일반도로(국도 구간이 도로명으로 표기된 경우도 포함).
 */
public final class RoadClass {
    public static final String MOTORWAY = "고속도로", URBAN_EXPRESS = "도시고속도로", NATIONAL = "국도", LOCAL = "일반도로";
    private static final Pattern NATIONAL_RE = Pattern.compile("국도|\\d+번\\s*국도|국지도");
    /** 서울의 대표 자동차전용도로 — 이름에 '고속'이 없어도 도시고속도로로 분류 */
    private static final Pattern URBAN_RE = Pattern.compile("^(올림픽대로|강변북로|동부간선도로|서부간선도로|내부순환로|북부간선도로|분당수서로)$");

    private RoadClass() {}

    public static String of(String name) {
        if (name == null || name.isBlank()) return LOCAL;
        String n = name.replace(" ", "");
        if (n.contains("고속도로")) return MOTORWAY;
        if (n.contains("도시고속") || n.contains("고속화") || URBAN_RE.matcher(n).matches()) return URBAN_EXPRESS;
        if (NATIONAL_RE.matcher(n).find()) return NATIONAL;
        return LOCAL;
    }

    /** 카카오 traffic_state: 0 정보 없음 · 1 정체 · 2 지체 · 3 서행 · 4 원활 · 6 사고 */
    public static String traffic(int state) {
        return switch (state) {
            case 1 -> "정체";
            case 2 -> "지체";
            case 3 -> "서행";
            case 4 -> "원활";
            case 6 -> "사고";
            default -> "정보 없음";
        };
    }
}
