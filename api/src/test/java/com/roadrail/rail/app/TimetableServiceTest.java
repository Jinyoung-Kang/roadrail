package com.roadrail.rail.app;

import com.roadrail.shared.Times;
import com.roadrail.external.TagoTrainClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 시간표 받기 — 공개 요청 하나가 기간(최대 366일)의 운행일마다 TAGO 를 부르지 않게 (RVW-03) */
class TimetableServiceTest {

    @Test
    void oneRequestSchedulesAtMostAMonthOfMissingDates() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).param(anyString(), any()).param(anyString(), any()).param(anyString(), any())
                .query(LocalDate.class).list()).thenReturn(List.of());   // 받아 둔 날짜 없음
        TagoTrainClient tago = mock(TagoTrainClient.class);
        when(tago.enabled()).thenReturn(true);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        var svc = new TimetableService(jdbc, tago, mock(ObjectMapper.class), exec);
        LocalDate today = Times.now().toLocalDate();
        List<LocalDate> year = IntStream.rangeClosed(1, 102).mapToObj(today::minusDays).toList();   // 실측: 오송→부산 366일 = 운행일 102

        boolean ready = svc.ensure("S1", "S2", year, Duration.ZERO);

        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);
        verify(tago, times(31 * 2)).nodeId(any());   // 받기 1건 = 출발 · 도착 역 노드 조회 2번. 수정 전 102건
        org.assertj.core.api.Assertions.assertThat(ready).isFalse();   // 나머지는 다음 요청이 이어 받는다
    }

    @Test
    void missingDatesComeMostRecentFirst() {
        LocalDate d = LocalDate.of(2026, 10, 3);
        var got = TimetableService.missingRecentFirst(List.of(d.minusDays(2), d, d.minusDays(1)), java.util.Set.of(d.minusDays(1)));
        org.assertj.core.api.Assertions.assertThat(got).containsExactly(d, d.minusDays(2));
    }

    @Test
    void hourlyLimitResetsEachHour() {
        var limit = new TimetableService.HourlyLimit(2);
        long t = 10 * 3_600_000L;
        org.assertj.core.api.Assertions.assertThat(limit.tryTake(t)).isTrue();
        org.assertj.core.api.Assertions.assertThat(limit.tryTake(t + 1)).isTrue();
        org.assertj.core.api.Assertions.assertThat(limit.tryTake(t + 2)).isFalse();              // 이 시간 몫을 다 씀
        org.assertj.core.api.Assertions.assertThat(limit.tryTake(t + 3_600_000L)).isTrue();      // 다음 시간
    }
}
