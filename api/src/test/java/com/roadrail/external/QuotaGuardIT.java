package com.roadrail.external;

import com.roadrail.shared.Times;
import com.roadrail.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/** 공유 외부 예산 — 한도 확인 · 예약 · 사용 기록을 Lua 한 번으로 (PERF-07). collector 와 같은 키를 쓴다 */
class QuotaGuardIT extends IntegrationTest {
    @Autowired
    QuotaGuard quota;

    private static String day() { return Times.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")); }

    @Test
    void takeReservesAndRecordsUseInOneStep() {
        for (int i = 0; i < 3; i++) assertThat(quota.take("KASI")).isTrue();
        assertThat(redis.opsForValue().get("quota:KASI:" + day())).isEqualTo("3");
        assertThat(redis.opsForValue().get("quota:used:KASI:" + day())).isEqualTo("3");
        assertThat(redis.getExpire("quota:KASI:" + day())).isPositive();       // 48시간 뒤 사라짐
        assertThat(redis.getExpire("quota:used:KASI:" + day())).isPositive();
    }

    @Test
    void stopsAtTheDailyLimitWithoutCounting() {
        // KASI 일일 한도 100(application.yml 기본) — 이미 다 쓴 날은 호출하지 않고, 누계 · 사용 수도 늘리지 않는다
        redis.opsForValue().set("quota:KASI:" + day(), "100");
        assertThat(quota.take("KASI")).isFalse();
        assertThat(redis.opsForValue().get("quota:KASI:" + day())).isEqualTo("100");
        assertThat(redis.opsForValue().get("quota:used:KASI:" + day())).isNull();
    }
}
