package com.roadrail.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class LatestDepartureTest {
    static final long DEADLINE = 50_400;   // 14:00 (하루 안의 초)

    /** 호출을 기록하는 예측 — 출발 시각에 따라 소요가 달라진다 */
    static final class Recording implements LatestDeparture.Eta {
        final List<Long> asked = new ArrayList<>();
        final LongToIntFunction f;
        Recording(LongToIntFunction f) { this.f = f; }
        @Override public Integer seconds(long t) { asked.add(t); return f.applyAsInt(t); }
    }

    @Test
    void findsLatestStepThatArrivesInTimeWithFewCalls() {
        // 12:00 이후 출발은 1시간 40분, 그 전은 1시간 20분 — 12:00 출발 → 13:40 도착, 12:10 출발 → 13:50 도착, 12:20 → 14:00
        var eta = new Recording(t -> t >= 43_200 ? 6_000 : 4_800);
        var r = LatestDeparture.search(eta, DEADLINE, 36_000, 5_400, 4);
        assertThat(r.depart()).isEqualTo(44_400);   // 12:20 → 14:00 (기한 포함)
        assertThat(r.depart() + r.durationSec()).isLessThanOrEqualTo(DEADLINE);
        assertThat(r.calls()).isLessThanOrEqualTo(4);
        assertThat(eta.asked).allMatch(t -> t % LatestDeparture.STEP_SEC == 0);
    }

    @Test
    void neverReturnsADepartureThatMissesTheDeadline() {
        for (int dur = 3_000; dur <= 9_000; dur += 700) {
            int d = dur;
            var r = LatestDeparture.search(t -> d + (int) ((t - 36_000) / 10), DEADLINE, 30_000, 4_000, 4);
            if (r.depart() != null) assertThat(r.depart() + r.durationSec()).isLessThanOrEqualTo(DEADLINE);
            assertThat(r.calls()).isLessThanOrEqualTo(4);
        }
    }

    @Test
    void infeasibleWhenEvenLeavingNowIsLate() {
        var r = LatestDeparture.search(t -> 7_200, DEADLINE, 46_800, 3_600, 4);   // 13:00 에 떠나도 15:00
        assertThat(r.depart()).isNull();
        assertThat(r.feasible()).isFalse();
        assertThat(r.pending()).isFalse();
    }

    @Test
    void pendingWhenForecastNotReadyYet() {
        var r = LatestDeparture.search(t -> null, DEADLINE, 36_000, 3_600, 4);
        assertThat(r.pending()).isTrue();
        assertThat(r.calls()).isEqualTo(1);
    }
}
