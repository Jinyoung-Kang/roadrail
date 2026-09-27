package com.roadrail.external;

import com.roadrail.common.ClientContext;
import com.roadrail.common.Times;
import com.roadrail.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공유 외부 예산 — 클라이언트 한 곳이 하루 예산을 다 쓰지 못하게 몫(기본 20%)을 둔다 (SEC-02).
 * 요청 수가 아니라 **실제 외부 호출**만 센다 → 캐시 적중 · 화면 자동 갱신은 몫을 쓰지 않는다.
 */
class QuotaGuardIT extends IntegrationTest {
    @Autowired
    QuotaGuard quota;

    private static String day() { return Times.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")); }

    @Test
    void eachClientGetsAtMostItsShareOfTheDailyBudget() {
        // KASI 일일 한도 100(application.yml 기본) × 20% → 한 클라이언트는 20건까지
        ClientContext.run("198.51.100.1", () -> {
            for (int i = 0; i < 20; i++) assertThat(quota.take("KASI")).as("호출 %d", i + 1).isTrue();
            assertThat(quota.take("KASI")).as("몫을 넘는 21번째").isFalse();
        });
        ClientContext.run("198.51.100.2", () -> assertThat(quota.take("KASI")).isTrue());  // 다른 클라이언트는 따로 센다
        assertThat(quota.take("KASI")).as("요청 밖 호출(클라이언트 없음)은 몫 제한 없음").isTrue();

        // 예약 누계 · 사용 수는 실제 호출 수와 같다 (거절된 호출은 세지 않음)
        assertThat(redis.opsForValue().get("quota:KASI:" + day())).isEqualTo("22");
        assertThat(redis.opsForValue().get("quota:used:KASI:" + day())).isEqualTo("22");
    }

    @Test
    void globalDailyLimitStillApplies() {
        redis.opsForValue().set("quota:KASI:" + day(), "100");
        assertThat(quota.take("KASI")).isFalse();
        ClientContext.run("198.51.100.3", () -> assertThat(quota.take("KASI")).isFalse());
        assertThat(redis.opsForValue().get("quota:client:KASI:" + day() + ":198.51.100.3")).isNull();  // 전체 한도로 거절되면 몫도 쓰지 않음
    }

    @Test
    void backgroundWorkInheritsTheRequestingClient() throws Exception {
        // 요청이 띄운 가상 스레드(예: 시간표 받기)도 그 클라이언트의 몫으로 센다
        ClientContext.run("198.51.100.4", () -> {
            try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                exec.submit(() -> quota.take("KASI")).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(redis.opsForValue().get("quota:client:KASI:" + day() + ":198.51.100.4")).isEqualTo("1");
    }
}
