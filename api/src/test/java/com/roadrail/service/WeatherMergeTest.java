package com.roadrail.service;

import com.roadrail.external.KmaClient;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 판단 화면 날씨: 단기예보 위에 초단기예보(가까운 출발) · 초단기실황(지금 출발)을 덮는다 */
class WeatherMergeTest {
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-28T17:20:00+09:00");
    static final TripService.Weather BASE = new TripService.Weather(30, "없음", 22, "구름많음", null, "단기예보");

    @Test
    void ultraShortReplacesTemperatureSkyAndPrecipitationButKeepsPop() {
        var ultra = new KmaClient.Forecast("2026-09-28T16:30+09:00",
                Map.of("2026092817", Map.of("PTY", "1", "T1H", "20", "SKY", "4", "RN1", "1mm 미만")));
        var w = TripService.mergeWeather(BASE, ultra, null, AT);
        assertThat(w).isEqualTo(new TripService.Weather(30, "비", 20, "흐림", "1mm 미만", "초단기예보 16:30 발표"));
    }

    @Test
    void nowcastWinsForLeavingNow() {
        var ultra = new KmaClient.Forecast("2026-09-28T16:30+09:00", Map.of("2026092817", Map.of("PTY", "0", "T1H", "20")));
        var now = new KmaClient.Observation("2026-09-28T16:00+09:00", Map.of("PTY", "1", "T1H", "18.6", "RN1", "2.5"));
        var w = TripService.mergeWeather(BASE, ultra, now, AT);
        assertThat(w.pty()).isEqualTo("비");
        assertThat(w.tmp()).isEqualTo(19);
        assertThat(w.rain()).isEqualTo("2.5mm");
        assertThat(w.source()).isEqualTo("초단기실황 16:00 관측");
        assertThat(w.pop()).isEqualTo(30);
    }

    @Test
    void missingHoursAndMissingValuesKeepTheShortTermForecast() {
        var ultra = new KmaClient.Forecast("2026-09-28T16:30+09:00", Map.of("2026092821", Map.of("PTY", "1")));
        assertThat(TripService.mergeWeather(BASE, ultra, null, AT)).isEqualTo(BASE);
        var bad = new KmaClient.Observation("2026-09-28T16:00+09:00", Map.of("PTY", "0", "T1H", "-998.9", "RN1", "-998.9"));
        var w = TripService.mergeWeather(BASE, null, bad, AT);
        assertThat(w.tmp()).isEqualTo(22);          // 결측 기온은 쓰지 않는다
        assertThat(w.rain()).isNull();
    }

    @Test
    void rainTexts() {
        assertThat(TripService.forecastRain("강수없음")).isNull();
        assertThat(TripService.forecastRain("30.0~50.0mm")).isEqualTo("30.0~50.0mm");
        assertThat(TripService.observedRain("0")).isNull();
        assertThat(TripService.observedRain("0.4")).isEqualTo("1mm 미만");
        assertThat(TripService.observedRain("12")).isEqualTo("12.0mm");
        assertThat(TripService.observedRain("x")).isNull();
    }

    @Test
    void currentHourComesFromThePreviousShortTermIssueRightAfterANewOne() {
        // 17시 발표는 18시부터 — 17:20 '지금 출발'은 14시 발표의 17시 값을 쓴다 (예전에는 날씨가 모두 비었다)
        var latest = new KmaClient.Forecast("2026-09-28T17:00+09:00", Map.of("2026092818", Map.of("POP", "20")));
        var previous = new KmaClient.Forecast("2026-09-28T14:00+09:00", Map.of("2026092817", Map.of("POP", "40", "TMP", "24")));
        assertThat(TripService.shortTermHour(latest, () -> previous, AT)).containsEntry("POP", "40");
        // 새 발표에 있으면 직전 발표는 부르지도 않는다
        assertThat(TripService.shortTermHour(previous, () -> { throw new AssertionError("호출하면 안 됨"); }, AT)).containsEntry("TMP", "24");
        assertThat(TripService.shortTermHour(null, () -> null, AT)).isNull();
    }
}
