package com.roadrail.ops.app;

import com.roadrail.ops.model.OpsDtos.Freshness;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpsFreshnessTest {
    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 10, 3, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void seriesIsStaleOnlyPastItsThresholdAndUnknownWhenEmpty() {
        // 통행시간 6시간 · 교통량 3시간 — 경계(= 임계)는 아직 신선, 넘으면 멈춤. 행이 없으면 모름(멈춤 아님)
        var f = OpsService.freshness(Map.of("road_travel_time", NOW.minusMinutes(360)), NOW);
        assertThat(f).extracting(Freshness::series).containsExactly("road_travel_time", "road_volume_all");
        assertThat(f.get(0).stale()).isFalse();
        assertThat(f.get(0).ageMin()).isEqualTo(360);
        assertThat(f.get(1).latestAt()).isNull();
        assertThat(f.get(1).stale()).isFalse();
        assertThat(OpsService.freshness(Map.of("road_travel_time", NOW.minusDays(5)), NOW).get(0).stale()).isTrue();
    }
}
