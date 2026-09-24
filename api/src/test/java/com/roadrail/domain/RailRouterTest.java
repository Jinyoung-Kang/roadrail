package com.roadrail.domain;

import com.roadrail.domain.RailRouter.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RailRouterTest {
    static long t(int h, int m) { return h * 3600L + m * 60L; }

    /** 전주(JJ) → 대전(DJ) 무궁화 A, 대전 → 동대구(DG) KTX B, 전주 → 동대구 직통 없음, 익산(IS) 경유 느린 C */
    static final List<Stop> STOPS = List.of(
            new Stop("A", 1, "JJ", null, t(6, 0)), new Stop("A", 2, "IS", t(6, 20), t(6, 22)), new Stop("A", 3, "DJ", t(7, 30), null),
            new Stop("B", 1, "SEL", null, t(6, 50)), new Stop("B", 2, "DJ", t(7, 40), t(7, 42)), new Stop("B", 3, "DG", t(8, 30), null),
            new Stop("B2", 1, "DJ", null, t(7, 35)), new Stop("B2", 2, "DG", t(8, 20), null),   // 환승 5분 → 못 탐
            new Stop("C", 1, "IS", null, t(6, 40)), new Stop("C", 2, "DG", t(10, 0), null));

    @Test
    void connectionsAreConsecutiveStopsSortedByDeparture() {
        var c = RailRouter.connections(STOPS);
        assertThat(c).hasSize(6);
        assertThat(c.getFirst()).isEqualTo(new Connection("A", "JJ", "IS", t(6, 0), t(6, 20)));
        assertThat(c).isSortedAccordingTo((x, y) -> Long.compare(x.dep(), y.dep()));
    }

    @Test
    void transferRespectsMinimumChangeTime() {
        var j = RailRouter.earliest(RailRouter.connections(STOPS), List.of(new Origin("JJ", t(5, 50))),
                List.of(new Dest("DG", 600)), 600).orElseThrow();
        // B2(07:35) 는 대전 도착 07:30 + 환승 10분 = 07:40 이전이라 못 타고, B(07:42) 로 환승 → 08:30
        assertThat(j.legs()).extracting(Leg::trip).containsExactly("A", "B");
        assertThat(j.transfers()).isEqualTo(1);
        assertThat(j.arrival()).isEqualTo(t(8, 30));
        assertThat(j.finalArrival()).isEqualTo(t(8, 40));
        assertThat(j.legs().get(1).from()).isEqualTo("DJ");
    }

    @Test
    void shorterTransferAllowsEarlierTrain() {
        var j = RailRouter.earliest(RailRouter.connections(STOPS), List.of(new Origin("JJ", t(5, 50))),
                List.of(new Dest("DG", 0)), 300).orElseThrow();
        assertThat(j.legs()).extracting(Leg::trip).containsExactly("A", "B2");
        assertThat(j.arrival()).isEqualTo(t(8, 20));
    }

    @Test
    void staysOnSameTrainWithoutPenaltyAndPicksBestOriginIncludingAccess() {
        // 익산에서 타면 C(10:00 도착) 보다 A→B 가 빠르다; 익산까지 접근이 길어 전주에서 타는 것이 유리
        var j = RailRouter.earliest(RailRouter.connections(STOPS),
                List.of(new Origin("JJ", t(5, 55)), new Origin("IS", t(6, 21))), List.of(new Dest("DG", 0)), 600).orElseThrow();
        assertThat(j.originStn()).isIn("JJ", "IS");
        assertThat(j.legs().getFirst().trip()).isEqualTo("A");
        assertThat(j.arrival()).isEqualTo(t(8, 30));
    }

    @Test
    void egressTimeDecidesDestinationStation() {
        // 대전(07:30 도착)에서 내려 60분 이동 vs 동대구(08:30)에서 내려 5분 이동 → 대전 08:30 이 동대구 08:35 보다 빠르다
        var j = RailRouter.earliest(RailRouter.connections(STOPS), List.of(new Origin("JJ", t(5, 50))),
                List.of(new Dest("DJ", 3600), new Dest("DG", 300)), 600).orElseThrow();
        assertThat(j.destStn()).isEqualTo("DJ");
        assertThat(j.finalArrival()).isEqualTo(t(8, 30));
    }

    @Test
    void noRouteWhenTooLateAndSeveralJourneysAreDistinct() {
        var conns = RailRouter.connections(STOPS);
        assertThat(RailRouter.earliest(conns, List.of(new Origin("JJ", t(6, 1))), List.of(new Dest("DJ", 0)), 600)).isEmpty();
        var several = RailRouter.several(conns, List.of(new Origin("JJ", t(5, 0)), new Origin("IS", t(5, 0))),
                List.of(new Dest("DG", 0)), 600, 3);
        assertThat(several).extracting(Journey::departure).doesNotHaveDuplicates();
        assertThat(several.size()).isGreaterThanOrEqualTo(2);
    }
}
