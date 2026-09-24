package com.roadrail.domain;

/** 위경도 → 기상청 동네예보 격자 (LCC). collector 의 providers/kma.py latlon_to_grid 와 같은 식. */
public final class KmaGrid {
    private static final double RE = 6371.00877, GRID = 5.0, SLAT1 = 30.0, SLAT2 = 60.0, OLON = 126.0, OLAT = 38.0;
    private static final double XO = 43, YO = 136;

    public record Cell(int nx, int ny) {}

    private KmaGrid() {}

    public static Cell of(double lat, double lon) {
        double d = Math.PI / 180.0;
        double re = RE / GRID, slat1 = SLAT1 * d, slat2 = SLAT2 * d, olon = OLON * d, olat = OLAT * d;
        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.pow(Math.tan(Math.PI * 0.25 + slat1 * 0.5), sn) * Math.cos(slat1) / sn;
        double ro = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + olat * 0.5), sn);
        double ra = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + lat * d * 0.5), sn);
        double theta = lon * d - olon;
        if (theta > Math.PI) theta -= 2.0 * Math.PI;
        if (theta < -Math.PI) theta += 2.0 * Math.PI;
        theta *= sn;
        return new Cell((int) Math.floor(ra * Math.sin(theta) + XO + 0.5), (int) Math.floor(ro - ra * Math.cos(theta) + YO + 0.5));
    }

    /** 두 지점 사이 직선거리 (km) */
    public static double km(double lat1, double lon1, double lat2, double lon2) {
        double p = Math.PI / 180;
        double h = Math.pow(Math.sin((lat2 - lat1) * p / 2), 2)
                + Math.cos(lat1 * p) * Math.cos(lat2 * p) * Math.pow(Math.sin((lon2 - lon1) * p / 2), 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
}
