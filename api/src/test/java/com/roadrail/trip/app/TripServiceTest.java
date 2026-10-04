package com.roadrail.trip.app;

import com.roadrail.corridor.app.CorridorService;
import com.roadrail.corridor.app.RoadService;
import com.roadrail.env.app.EnvService;
import com.roadrail.env.app.HolidayService;
import com.roadrail.rail.app.RailService;
import com.roadrail.shared.JsonCache;
import com.roadrail.shared.AppProperties;
import com.roadrail.external.AirKoreaClient;
import com.roadrail.external.KakaoLocalClient;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.external.KmaClient;
import com.roadrail.corridor.model.NowDtos;
import com.roadrail.trip.model.TripDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 판단 카드 — 보조 조회(철도 · 길 매칭 · 날씨)가 늦거나 실패해도 '없음'으로 굳혀 캐시하지 않는다 (RVW-01) */
class TripServiceTest {
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final JsonCache cache = mock(JsonCache.class);
    private final RailJourneyService journeys = mock(RailJourneyService.class);
    private final KakaoMobilityClient mobility = mock(KakaoMobilityClient.class);
    private final TripService svc = spy(new TripService(mock(CorridorService.class), mock(AppProperties.class),
            mock(RailService.class), mock(RoadService.class), mock(EnvService.class), mobility, mock(KakaoLocalClient.class),
            mock(KmaClient.class), mock(AirKoreaClient.class), cache, journeys, mock(HolidayService.class), exec));
    private final TripDtos.Place seoul = new TripDtos.Place("서울역", null, 37.5547, 126.9707, "STATION", null);
    private final TripDtos.Place daejeon = new TripDtos.Place("대전역", null, 36.3326, 127.4342, "STATION", null);

    TripServiceTest() {
        when(mobility.futureEta(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyBoolean())).thenReturn(Optional.empty());
        doReturn(new NowDtos.PointEnv("x", null, null, null, null, null, null, null, null, null)).when(svc).pointEnv(any(), any());
        doReturn(null).when(svc).observed(any(), any(), any(), any());
    }

    @AfterEach
    void close() {
        exec.close();
    }

    @Test
    void railStillComputingIsPendingAndNotCached() {
        // 철도 계산이 4초 마감을 넘기면 예전에는 '열차 없음'과 같아져 "자동차만 비교" 결론을 60초 캐시했다
        when(journeys.plan(any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(4500);
            return null;
        });
        var t = svc.trip(seoul, daejeon, 0, null);
        assertThat(t.pending()).isTrue();
        assertThat(t.decision().summary()).doesNotContain("이어지는 열차가 없어");
        verify(cache, never()).put(anyString(), any(), any());
    }

    @Test
    void failingSideLookupDoesNotFailTheWholeCard() {
        // 길 매칭(DB)의 일시 오류가 /trip 전체를 500 으로 만들었다 → 비우고 다시 묻게(pending)
        doThrow(new IllegalStateException("db down")).when(svc).observed(any(), any(), any(), any());
        var t = svc.trip(seoul, daejeon, 0, null);
        assertThat(t.pending()).isTrue();
        verify(cache, never()).put(anyString(), any(), any());
    }
}
