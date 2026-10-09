package com.roadrail.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class DelayDistributionTest {
    @Test
    void probabilityIsShareOfRunsWithinMarginInclusive() {
        var d = DelayDistribution.of(Arrays.asList(5.0, 0.0, 12.0, 3.0, null, 30.0));
        assertThat(d.n()).isEqualTo(5);
        assertThat(d.countWithin(5)).isEqualTo(3);   // 0 · 3 · 5 (경계 포함)
        assertThat(d.probWithin(5)).isEqualTo(0.6);
        assertThat(d.probWithin(-1)).isZero();
        assertThat(d.max()).isEqualTo(30.0);
        assertThat(d.allWithin(29.9)).isFalse();
        assertThat(d.allWithin(30)).isTrue();
    }

    @Test
    void emptyDistributionIsUnknownNotZero() {
        var d = DelayDistribution.of();
        assertThat(d.probWithin(10)).isNaN();
        assertThat(d.allWithin(10)).isFalse();
        assertThat(d.max()).isNaN();
    }
}
