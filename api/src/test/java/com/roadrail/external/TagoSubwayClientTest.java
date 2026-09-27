package com.roadrail.external;

import com.roadrail.external.TagoSubwayClient.Departure;
import com.roadrail.external.TagoSubwayClient.SubwayStation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** TAGO 지하철 응답 해석 — 오류 응답을 '결과 없음'으로 캐시하지 않고, 잘못된 행 하나가 전체를 실패시키지 않게 (BUG-03) */
class TagoSubwayClientTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void errorHeaderIsAFailureNotAnEmptyResult() {
        // 예전에는 빈 목록으로 보고 역 목록 7일 · 시간표 1일 동안 캐시했다 → 일시 장애가 오래 남음
        var body = JSON.readTree("""
                {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY IS NOT REGISTERED ERROR."}}}""");
        assertThat(TagoSubwayClient.items(body)).isNull();
        assertThat(TagoSubwayClient.items(null)).isNull();
    }

    @Test
    void normalResponsesBecomeArrays() {
        var none = JSON.readTree("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":""}}}""");
        assertThat(TagoSubwayClient.items(none)).isEmpty();
        var one = JSON.readTree("""
                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":{"depTime":"051200"}}}}}""");
        assertThat(TagoSubwayClient.items(one)).hasSize(1);
    }

    @Test
    void malformedDepartureRowsAreSkipped() {
        var items = JSON.readTree("""
                [{"depTime":"051200","endSubwayStationNm":"소요산"}, {"depTime":"xx3000"}, {"depTime":"25"},
                 {"depTime":"241000","endSubwayStationNm":"인천"}, {"depTime":"0599"}]""");
        var deps = TagoSubwayClient.departures(items, new SubwayStation("SES1001", "서울역", "1호선"));
        // 24시 10분 = 다음 날 00:10 (운행일 기준 표기) · 형식이 틀린 행(xx30 · 25 · 05:99)은 건너뜀 · 시각 순
        assertThat(deps).containsExactly(new Departure("1호선", "인천", LocalTime.of(0, 10)),
                new Departure("1호선", "소요산", LocalTime.of(5, 12)));
    }
}
