package com.roadrail.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationRuleTest {
    @Test
    void countsAreInclusiveAtEachThresholdAndSkipUnknownDelays() {
        // AR-3 · A11: 약관은 '20분 이상' — 19분은 넘지 않고 20분은 넘는다. 계획 시각을 모르는 운행(null)은 세지 않는다
        int[] c = CompensationRule.counts(Arrays.asList(0.0, 19.0, 20.0, 45.0, 61.0, 125.0, null));
        assertThat(c).containsExactly(4, 3, 2, 1, 1);
    }

    @Test
    void sqlCountsComeFromTheSameThresholds() {
        assertThat(CompensationRule.sqlCounts("d")).isEqualTo(
                "count(*) FILTER (WHERE d >= 20) AS ge20, count(*) FILTER (WHERE d >= 40) AS ge40, "
                        + "count(*) FILTER (WHERE d >= 60) AS ge60, count(*) FILTER (WHERE d >= 90) AS ge90, "
                        + "count(*) FILTER (WHERE d >= 120) AS ge120");
        assertThat(CompensationRule.counts(List.of())).containsExactly(0, 0, 0, 0, 0);
    }
}
