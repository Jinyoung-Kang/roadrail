package com.roadrail.external;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 기상청 초단기예보 · 실황: 제공 시각에 맞춘 발표 시각, 오류 응답은 캐시하지 않는 null, 필요한 항목만 */
class KmaClientTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static OffsetDateTime kst(String s) {
        return OffsetDateTime.parse(s + "+09:00");
    }

    @Test
    void ultraShortBaseIsTheLatestHalfPastAvailable() {
        // 매시 30분 발표 · 45분부터 제공
        assertThat(KmaClient.ultraBase(kst("2026-09-28T17:14:00"))).isEqualTo(kst("2026-09-28T15:30:00"));
        assertThat(KmaClient.ultraBase(kst("2026-09-28T17:15:00"))).isEqualTo(kst("2026-09-28T16:30:00"));
        assertThat(KmaClient.ultraBase(kst("2026-09-29T00:10:00"))).isEqualTo(kst("2026-09-28T22:30:00"));
    }

    @Test
    void nowcastBaseIsTheLatestHourAvailable() {
        // 매시 정각 관측 · 40분부터 제공
        assertThat(KmaClient.nowcastBase(kst("2026-09-28T17:39:00"))).isEqualTo(kst("2026-09-28T16:00:00"));
        assertThat(KmaClient.nowcastBase(kst("2026-09-28T17:40:00"))).isEqualTo(kst("2026-09-28T17:00:00"));
        assertThat(KmaClient.nowcastBase(kst("2026-09-29T00:20:00"))).isEqualTo(kst("2026-09-28T23:00:00"));
    }

    @Test
    void errorOrEmptyResponsesAreNotCachedAsResults() {
        assertThat(KmaClient.items(JSON.readTree("""
                {"response":{"header":{"resultCode":"03","resultMsg":"NO_DATA"}}}"""))).isNull();
        assertThat(KmaClient.items(JSON.readTree("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[]}}}}"""))).isNull();
        assertThat(KmaClient.items(null)).isNull();
    }

    @Test
    void ultraShortForecastKeepsOnlyUsedCategoriesByHour() {
        var body = JSON.readTree("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},"body":{"items":{"item":[
                  {"category":"PTY","fcstDate":"20260928","fcstTime":"1700","fcstValue":"1"},
                  {"category":"RN1","fcstDate":"20260928","fcstTime":"1700","fcstValue":"1mm 미만"},
                  {"category":"T1H","fcstDate":"20260928","fcstTime":"1700","fcstValue":"23"},
                  {"category":"UUU","fcstDate":"20260928","fcstTime":"1700","fcstValue":"1.2"},
                  {"category":"PTY","fcstDate":"20260928","fcstTime":"1800","fcstValue":"0"}]}}}}""");
        var f = KmaClient.forecastItems(KmaClient.items(body), kst("2026-09-28T16:30:00"), Set.of("T1H", "SKY", "PTY", "RN1"));
        assertThat(f.hours()).containsOnlyKeys("2026092817", "2026092818");
        assertThat(f.hours().get("2026092817")).containsOnlyKeys("PTY", "RN1", "T1H").containsEntry("RN1", "1mm 미만");
    }

    @Test
    void nowcastKeepsObservedValues() {
        var body = JSON.readTree("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                  {"category":"PTY","obsrValue":"1"},{"category":"RN1","obsrValue":"2.5"},
                  {"category":"T1H","obsrValue":"19.4"},{"category":"REH","obsrValue":"90"}]}}}}""");
        var o = KmaClient.observation(KmaClient.items(body), kst("2026-09-28T16:00:00"));
        assertThat(o.values()).containsOnlyKeys("PTY", "RN1", "T1H").containsEntry("RN1", "2.5");
    }
}
