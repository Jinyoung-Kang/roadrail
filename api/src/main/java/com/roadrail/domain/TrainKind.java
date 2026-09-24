package com.roadrail.domain;

/**
 * 열차 종류 추정 (T-v1) — 코레일 API 에는 열차 종류(KTX·무궁화 …)가 없어 **열차 번호 체계**로 추정한다.
 * <pre>
 *   1–299, 400–599     KTX          (경부·경전·동해·호남·전라)
 *   300–399, 600–699   SRT          (수서 발착)
 *   700–799            KTX-이음     (중앙·중부내륙)
 *   800–899            KTX          (강릉·동해)
 *   1000–1099          ITX-새마을
 *   1100–1199          ITX-마음·새마을
 *   1200–1999, 2600–   무궁화호
 *   2000–2599          ITX-청춘
 *   4000–             임시 열차 — 수서 발착이면 SRT, 평균 직선 속도 ≥ 90km/h 면 KTX, 아니면 일반 열차
 * </pre>
 * 번호 체계는 코레일이 바꿀 수 있어 화면에는 항상 '추정'으로 표시한다.
 */
public final class TrainKind {
    private TrainKind() {}

    public static String of(String trnNo, String origin, String terminus, Double straightKmh) {
        int n;
        try {
            n = Integer.parseInt(trnNo.trim());
        } catch (NumberFormatException e) {
            return "열차";
        }
        boolean suseo = "수서".equals(origin) || "수서".equals(terminus);
        if (n >= 4000) {
            String k = suseo ? "SRT" : straightKmh != null && straightKmh >= 90 ? "KTX" : "일반 열차";
            return k + " (임시)";
        }
        if (n < 1000) {
            if (n >= 300 && n < 400 || n >= 600 && n < 700) return suseo ? "SRT" : "KTX";
            if (n >= 700 && n < 800) return "KTX-이음";
            return "KTX";
        }
        if (n < 1100) return "ITX-새마을";
        if (n < 1200) return "ITX-마음·새마을";
        if (n < 2000) return "무궁화호";
        if (n < 2600) return "ITX-청춘";
        return "무궁화호";
    }
}
