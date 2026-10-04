package com.roadrail.service;

import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.web.dto.TripDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 경로 분석 — 새 조합 하나의 카카오 호출 수 (RVW-07) */
class RoadRouteServiceTest {

    @Test
    void zeroMinuteProfileReusesTheRecommendedRoute() {
        // 출발 0분 프로필(요약)이 추천 경로(상세)와 같은 출발 시각을 따로 불러 새 조합 하나에 카카오 10건이었다
        KakaoMobilityClient kakao = mock(KakaoMobilityClient.class);
        var route = new KakaoMobilityClient.Route(3600, 100_000, null, null, "202610041700", List.of(), List.of(), List.of());
        when(kakao.route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), anyBoolean())).thenReturn(route);
        TripService trips = mock(TripService.class);
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var svc = new RoadRouteService(kakao, trips, exec);
            var a = svc.analyze(new TripDtos.Place("서울역", null, 37.5547, 126.9707, "STATION", null),
                    new TripDtos.Place("대전역", null, 36.3326, 127.4342, "STATION", null), 0);
            verify(kakao, times(RoadRouteService.PROFILE_OFFSETS_MIN.length + 1))
                    .route(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any(), anyBoolean());   // 추천 · 회피 + 프로필(0분 제외)
            assertThat(a.profile()).hasSize(RoadRouteService.PROFILE_OFFSETS_MIN.length);
            assertThat(a.profile().getFirst().durationSec()).isEqualTo(3600);   // 0분 = 추천 경로의 소요
        }
    }
}
