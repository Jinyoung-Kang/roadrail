package com.roadrail.domain;

/**
 * 카카오 경로의 도로 이름 → 도로 구분. 카카오 응답에는 도로 등급이 없어, 이름에 '고속도로'가 들어간 구간만 고속도로로 구분한다.
 * 국도·지방도·시내 도로는 이름(예: 경수대로 · 남북대로)만으로는 알 수 없으므로 나누지 않고 '그 외 도로'로 둔다 (추정하지 않음).
 */
public final class RoadClass {
    public static final String MOTORWAY = "고속도로", OTHER = "그 외 도로";

    private RoadClass() {}

    public static String of(String name) {
        return name != null && name.replace(" ", "").contains("고속도로") ? MOTORWAY : OTHER;
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
