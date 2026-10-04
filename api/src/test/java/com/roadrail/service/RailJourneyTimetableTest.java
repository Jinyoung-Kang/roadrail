package com.roadrail.service;

import com.roadrail.domain.RailRouter;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.external.TagoSubwayClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

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
            var svc = spy(new RailJourneyService(mock(JdbcClient.class), rail, mock(KakaoMobilityClient.class),
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
}
