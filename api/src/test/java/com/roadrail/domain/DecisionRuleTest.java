package com.roadrail.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRuleTest {
    static final DecisionRule.Params P = new DecisionRule.Params(10, 60, 20);

    static DecisionRule.Train train(int wait, int ride, Double delay) {
        return new DecisionRule.Train("00101", "17:28", wait, ride, delay, 0.91, 28, false);
    }

    @Test
    void trainFasterWhenRoadCongested() {
        var car = new DecisionRule.Car(7260, "M1", 30.1, 0, "관측 3시간 전");
        // 기차 = 접근 20 + 대기 8 + 소요 59 + 지연 2.4 = 89.4 → 89분, 자동차 121분
        var r = DecisionRule.decide(car, train(8, 59, 2.4), null, 0, P);
        assertThat(r.verdict()).isEqualTo("TRAIN");
        assertThat(r.carTotalMin()).isEqualTo(121);
        assertThat(r.trainTotalMin()).isEqualTo(89);
        assertThat(r.summary()).isEqualTo("기차가 약 32분 빠를 것으로 보입니다.");
        assertThat(r.reasons()).anyMatch(s -> s.contains("중앙값보다 30% 김"));
        assertThat(r.reasons()).anyMatch(s -> s.contains("정시율 91%"));
    }

    @Test
    void similarWithinTenMinutes() {
        var car = new DecisionRule.Car(92 * 60, "M1", -2.0, 0, "");
        var r = DecisionRule.decide(car, train(5, 60, 1.0), null, 0, P);
        assertThat(r.verdict()).isEqualTo("SIMILAR");
        assertThat(r.diffMin()).isEqualTo(6);
    }

    @Test
    void carFasterAndEarlyArrivalDoesNotShortenTrain() {
        var car = new DecisionRule.Car(50 * 60, "M0", null, 5, "");
        var r = DecisionRule.decide(car, train(30, 60, -1.5), null, 0, P);
        assertThat(r.trainTotalMin()).isEqualTo(110);  // 조기 도착(음수) 평균은 0 으로 본다
        assertThat(r.verdict()).isEqualTo("CAR");
        assertThat(r.summary()).isEqualTo("자동차가 약 55분 빠를 것으로 보입니다.");  // 50+5 vs 20+30+60
    }

    @Test
    void warningsForRainIncidentAndDust() {
        var env = new DecisionRule.Env(70, 20, 1, 3);
        var r = DecisionRule.decide(new DecisionRule.Car(6000, "M1", 0.0, 0, ""), train(5, 60, 1.0), env, 2, P);
        assertThat(r.warnings()).containsExactly("강수확률 70% — 도로 지연 가능성", "초미세먼지 나쁨", "코리도 관련 돌발 안내 2건");
    }

    @Test
    void neverConcludesWithoutBothSides() {
        var r = DecisionRule.decide(null, train(5, 60, 1.0), null, 0, P);
        assertThat(r.verdict()).isEqualTo("UNKNOWN");
        assertThat(r.reasons()).isNotEmpty();
        assertThat(r.summary()).contains("도로");
    }
}
