package com.roadrail.domain;

import com.roadrail.domain.ArrivalOdds.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ArrivalOddsTest {
    /** n 회 중 late 회만 delay 분 늦고 나머지는 정시(0) */
    static DelayDistribution runs(int n, int late, double delay) {
        return DelayDistribution.of(IntStream.range(0, n).mapToDouble(i -> i < late ? delay : 0).toArray());
    }

    @Test
    void singleLegGivesFrequencyAndFlooredPercent() {
        var o = ArrivalOdds.of(List.of(new Step(runs(30, 3, 20), 10)));
        assertThat(o.probability()).isEqualTo(0.9);
        assertThat(o.percent()).isEqualTo(90);
        assertThat(o.within()).isEqualTo(27);
        assertThat(o.n()).isEqualTo(30);
        assertThat(o.meets(0.9)).isTrue();
        assertThat(o.meets(0.95)).isFalse();
    }

    @Test
    void transferJourneyMultipliesLegProbabilities() {
        // 앞 열차가 환승 여유 안에 올 확률 0.9 × 마지막 열차가 기한 안에 올 확률 0.8 = 0.72 (독립 가정)
        var o = ArrivalOdds.of(List.of(new Step(runs(20, 2, 15), 5), new Step(runs(20, 4, 15), 5)));
        assertThat(o.probability()).isCloseTo(0.72, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(o.percent()).isEqualTo(72);
        assertThat(o.within()).isNull();
        assertThat(o.n()).isEqualTo(20);
    }

    @Test
    void fewSamplesGiveNoPercentButStillFrequency() {
        var o = ArrivalOdds.of(List.of(new Step(runs(14, 1, 20), 10)));
        assertThat(o.percent()).isNull();
        assertThat(o.within()).isEqualTo(13);
        assertThat(ArrivalOdds.of(List.of(new Step(runs(10, 0, 0), 10))).meets(0.8)).isFalse();   // 10회 모두 기한 안이어도 근거 부족
        assertThat(ArrivalOdds.of(List.of(new Step(runs(15, 1, 20), 10))).percent()).isEqualTo(93);
    }

    @Test
    void allObservedWithinIsNotShownAsHundredPercent() {
        var o = ArrivalOdds.of(List.of(new Step(runs(40, 5, 8), 10)));
        assertThat(o.allObservedWithin()).isTrue();
        assertThat(o.percent()).isNull();
        assertThat(o.probability()).isEqualTo(1.0);
    }

    @Test
    void missingRecordsMakeOddsUnknown() {
        var o = ArrivalOdds.of(List.of(new Step(runs(30, 0, 0), 5), new Step(DelayDistribution.of(), 5)));
        assertThat(o.known()).isFalse();
        assertThat(o.meets(0.8)).isFalse();
        assertThat(o.percent()).isNull();
    }
}
