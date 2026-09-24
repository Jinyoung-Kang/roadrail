package com.roadrail.web.dto;

import com.roadrail.domain.DecisionRule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 어디서 → 어디로 (자유 선택) 판단 카드 */
public final class TripDtos {
    private TripDtos() {}

    /** kind: REGION(행정구역) · STATION(기차역) · PLACE(장소) · ADDRESS · CORRIDOR(자주 오가는 길 끝점) */
    public record Place(String name, String address, double lat, double lon, String kind, String stationCode) {}

    public record PlaceSearch(String q, List<Place> items) {}

    public record Car(Integer durationSec, Integer distanceM, String departAt, List<double[]> path, boolean pending,
                      String source) {}

    /** 수집 중인 길과 겹칠 때만: 고속도로 실측 */
    public record Observed(String corridorId, String corridorName, String direction, Integer travelSec,
                           Integer baselineP50Sec, Double vsBaselinePct, OffsetDateTime slotTs, Integer predictedSec,
                           String model, int leadMin) {}

    public record Trip(TripDtos.Place from, TripDtos.Place to, double distanceKm, OffsetDateTime asOf,
                       OffsetDateTime departAt, Integer accessMin, Car car, Observed observed, JourneyDtos.Plan rail,
                       Map<String, NowDtos.PointEnv> env, List<EnvDtos.Incident> incidents, DecisionRule.Result decision,
                       Map<String, String> freshness, String caveat, boolean pending, String cache) {

        public Trip withCache(String c) {
            return new Trip(from, to, distanceKm, asOf, departAt, accessMin, car, observed, rail, env, incidents, decision,
                    freshness, caveat, pending, c);
        }
    }
}
