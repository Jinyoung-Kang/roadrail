package com.roadrail.domain;

import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.trip.app.RoadRouteServiceTestAccess;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoadClassTest {

    @ParameterizedTest
    @CsvSource({"호남고속도로,고속도로", "새만금포항고속도로지선(익산-완주),고속도로", "수도권제1순환고속도로,고속도로",
            "경수대로,그 외 도로", "남북대로,그 외 도로", "올림픽대로,그 외 도로", "'',그 외 도로"})
    void classifiesByName(String name, String type) {
        assertThat(RoadClass.of(name)).isEqualTo(type);
    }

    @Test
    void summaryMergesSameNamedRunsAndComputesShares() {
        var r = new KakaoMobilityClient.Route(3600, 100_000, 5000, 90000, "202609251000", List.of(), List.of(), List.of(
                new KakaoMobilityClient.Road("기린대로", 1000, 120, 4),
                new KakaoMobilityClient.Road("호남고속도로", 40000, 1200, 4),
                new KakaoMobilityClient.Road("호남고속도로", 50000, 1800, 1),     // 같은 이름은 합치고 소통은 나쁜 쪽
                new KakaoMobilityClient.Road("남북대로", 9000, 480, 2)));
        var s = RoadRouteServiceTestAccess.summarize(r);
        assertThat(s.roads()).extracting("name").containsExactly("기린대로", "호남고속도로", "남북대로");
        assertThat(s.roads().get(1).distanceM()).isEqualTo(90000);
        assertThat(s.roads().get(1).traffic()).isEqualTo("정체");
        assertThat(s.roads().get(1).trafficM()).isEqualTo(50000);         // 90km 중 정체는 50km
        assertThat(s.byType()).extracting("type").containsExactly("고속도로", "그 외 도로");
        assertThat(s.byType().getFirst().share()).isEqualTo(0.9);
        assertThat(s.slow()).extracting("name").containsExactly("호남고속도로", "남북대로");
        // 느린 구간은 소통이 같은 도로만 합친다 — 호남고속도로 원활 40km 는 빠지고 정체 50km 만
        assertThat(s.slow()).extracting("distanceM").containsExactly(50000, 9000);
        assertThat(s.slow()).extracting("traffic").containsExactly("정체", "지체");
    }
}
