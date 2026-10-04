package com.roadrail.external;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 카카오 길찾기 응답 → 경로 좌표 + 구간별 소통 (vertexes 는 경도 · 위도 순서) */
class KakaoRouteGeometryTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void vertexesAndTrafficStateBecomeColoredRuns() {
        var route = JSON.readTree("""
                {"sections":[
                  {"roads":[
                    {"name":"세종대로","traffic_state":4,"distance":120,"vertexes":[126.9700,37.5550, 126.9710,37.5560]},
                    {"name":"한강대로","traffic_state":1,"distance":1150,"vertexes":[126.9710,37.5560, 126.9720,37.5500, 126.9730,37.5450]}]},
                  {"roads":[
                    {"name":"경부고속도로","traffic_state":6,"distance":17200,"vertexes":[126.9730,37.5450, 127.0500,37.4000]}]}]}""");
        var g = KakaoMobilityClient.geometry(route);

        assertThat(g.path().getFirst()).containsExactly(37.5550, 126.9700);   // [위도, 경도]
        assertThat(g.path().getLast()).containsExactly(37.4000, 127.0500);
        assertThat(g.traffic()).extracting(t -> t.traffic()).containsExactly("원활", "정체", "사고");
        assertThat(g.traffic()).extracting(t -> t.distanceM()).containsExactly(120, 1150, 17200);
        assertThat(g.traffic().getFirst().from()).isZero();
        assertThat(g.traffic().getLast().to()).isEqualTo(g.path().size() - 1);
        assertThat(g.traffic().get(1).from()).isEqualTo(g.traffic().get(0).to());   // 경계 점 공유
    }

    @Test
    void missingSectionsGiveAnEmptyGeometry() {
        var g = KakaoMobilityClient.geometry(JSON.readTree("{}"));
        assertThat(g.path()).isEmpty();
        assertThat(g.traffic()).isEmpty();
    }

    @Test
    void onlyAnExplicitNonZeroResultCodeMeansNoRoute() {
        // 리뷰: result_code 가 없는 200 응답(형식 변경 · 잘린 응답)도 asInt(-1) 로 '경로 없음'이 되어 10분 캐시됐다
        assertThat(KakaoMobilityClient.routeFound(JSON.readTree("{\"routes\":[{\"result_code\":0}]}"))).isTrue();
        assertThat(KakaoMobilityClient.routeFound(JSON.readTree("{\"routes\":[{\"result_code\":104,\"result_msg\":\"출발지와 도착지가 너무 가까움\"}]}"))).isFalse();
        assertThat(KakaoMobilityClient.routeFound(JSON.readTree("{\"routes\":[{}]}"))).isNull();
        assertThat(KakaoMobilityClient.routeFound(JSON.readTree("{\"msg\":\"internal\"}"))).isNull();
        assertThat(KakaoMobilityClient.routeFound(null)).isNull();
    }
}
