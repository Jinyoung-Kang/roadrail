package com.roadrail.trip.app;

import com.roadrail.env.app.HolidayService;
import com.roadrail.rail.app.RailNetworkService;
import com.roadrail.rail.app.RailService;
import com.roadrail.rail.app.TimetableService;
import com.roadrail.domain.RailRouter;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.external.TagoSubwayClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** CSA 에 넣는 오늘 + 내일 시간표 — 두 날이 같은 기준일로 풀려도 열차 id 가 겹치지 않는다 (RVW-09) */
class RailJourneyTimetableTest {

    @Test
    void todayAndTomorrowFromTheSameReferenceDateGetDistinctTrips() {
        RailService rail = mock(RailService.class);
        LocalDate ref = LocalDate.of(2026, 9, 27);
        // 같은 요일 자료가 없으면 두 날 모두 가장 최근 운행일로 풀린다(RailService.referenceDate 의 대체)
        when(rail.referenceDate(any())).thenReturn(Optional.of(Map.entry(ref, "최근 운행일")));
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var svc = spy(new RailJourneyService(mock(RailNetworkService.class), rail, mock(KakaoMobilityClient.class),
                    mock(TagoSubwayClient.class), mock(TimetableService.class), mock(HolidayService.class), exec));
            doReturn(List.of(new RailRouter.Connection("101", "A", "B", 36_000, 39_600))).when(svc).connectionsFor(ref);
            var conns = svc.timetable(LocalDate.of(2026, 10, 4), new ArrayList<>(), new ArrayList<>());
            assertThat(conns).hasSize(2);
            assertThat(conns.get(0).trip()).isNotEqualTo(conns.get(1).trip());   // 예전: 둘 다 101@2026-09-27 → 24시간 '계속 탑승'
            assertThat(conns).allSatisfy(c -> {
                assertThat(RailJourneyService.trainNo(c.trip())).isEqualTo("101");
                assertThat(RailJourneyService.refDate(c.trip())).isEqualTo(ref);
            });
        }
    }

    @Test
    void legTimetableStillLoadingMakesThePlanPendingSoTheCardIsNotCached() {
        // 리뷰: 공유 마감(3.5초) 안에 구간 시간표 · 30일 통계가 오지 않으면 CSA 보간 시각 · 지연 0 으로 기차 소요를 적게 잡고,
        // Plan.pending 은 역까지 이동만 반영해 판단 카드가 그 값으로 60초 캐시됐다 — RVW-01 의 '늦으면 없음'과 같은 꼴
        RailService rail = mock(RailService.class);
        LocalDate ref = LocalDate.of(2026, 9, 27);
        when(rail.referenceDate(any())).thenReturn(Optional.of(Map.entry(ref, "같은 요일 최근 운행일")));
        TimetableService tt = mock(TimetableService.class);
        doAnswer(inv -> {   // 구간 시간표 받기(대기 있음)만 느리다 — 통계용 미리 받기(대기 0)는 바로
            if (((java.time.Duration) inv.getArgument(3)).isPositive()) Thread.sleep(5_000);
            return true;
        }).when(tt).ensure(any(), any(), any(), any());
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var svc = spy(new RailJourneyService(mock(RailNetworkService.class), rail, mock(KakaoMobilityClient.class),
                    mock(TagoSubwayClient.class), tt, mock(HolidayService.class), exec));
            long base = ref.atStartOfDay(com.roadrail.shared.Times.KST).toEpochSecond();
            doReturn(List.of(new RailRouter.Connection("101", "A", "B", base + 10 * 3600, base + 11 * 3600))).when(svc).connectionsFor(ref);
            doReturn(new RailJourneyService.TrackPath(List.of(), false)).when(svc).legPath(any(), any(), any(), any());
            doReturn(null).when(svc).coords(any());
            var from = new com.roadrail.trip.model.TripDtos.Place("A역", null, 37.5, 127.0, "STATION", "A");
            var to = new com.roadrail.trip.model.TripDtos.Place("B역", null, 36.3, 127.4, "STATION", "B");
            long t0 = System.nanoTime();
            var plan = svc.plan(from, to, java.time.OffsetDateTime.of(2026, 10, 4, 9, 0, 0, 0, java.time.ZoneOffset.ofHours(9)), null);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - t0)).isLessThan(java.time.Duration.ofMillis(4_500));
            assertThat(plan.journeys()).isNotEmpty();                                       // 오늘 · 내일 열차
            assertThat(plan.journeys().getFirst().legs().getFirst().timetable()).isFalse();   // 보간 시각 그대로
            assertThat(plan.pending()).isTrue();                                            // → 캐시하지 않고 화면이 다시 부름
        }
    }
}
