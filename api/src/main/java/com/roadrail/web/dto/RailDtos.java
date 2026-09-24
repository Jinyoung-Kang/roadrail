package com.roadrail.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class RailDtos {
    private RailDtos() {}

    public record TrainStats(int samples, int verified, Double onTimeRate, Double avgArrDelayMin, Double p90ArrDelayMin,
                             Double avgRideMin) {}

    public record NextTrain(String trnNo, OffsetDateTime planDepAt, OffsetDateTime planArrAt, String planDep, String planArr,
                            int planRideMin, Double avgArrDelayMin30d, Double onTimeRate30d, int samples) {}

    public record NextTrains(String referenceDate, String basis, List<NextTrain> trains) {}

    public record Station(String code, String name, Double lat, Double lon, int trains7d) {}

    public record StationNear(String code, String name, double lat, double lon, double distanceKm) {}

    /** 운행계획의 시발·종착역 — label = 'OO발 OO행' (코레일 API 에 열차 종류는 없어 넣지 않는다) */
    public record TrainMeta(String origin, String terminus, String label) {}

    /** grade = 그날 배정 차종 (TAGO 시간표 · 받은 날짜만, 없으면 null). basis: EXACT(운행계획) · TT(TAGO 시간표) · NONE(계획 시각 모름) */
    public record TrainRun(String trnNo, OffsetDateTime actDepAt, OffsetDateTime actArrAt, OffsetDateTime planDepAt,
                           OffsetDateTime planArrAt, Double depDelayMin, Double arrDelayMin, String depBasis, String arrBasis,
                           double rideMin, Boolean onTime, TrainStats stats30d, TrainMeta meta, String grade) {}

    public record Trains(String depCode, String arrCode, String depStation, String arrStation, String date,
                         List<TrainRun> trains, List<String> availableDates, String note) {}

    public record PunctualityItem(String key, int samples, int verified, Double onTimeRate, Double avgArrDelayMin,
                                  Double p90ArrDelayMin, Double avgRideMin, TrainMeta meta, String grade) {}

    public record Bucket(String label, int count) {}

    public record Summary(int samples, int verified, int unverified, Double onTimeRate, Double avgArrDelayMin,
                          Double p90ArrDelayMin) {}

    public record Punctuality(String depCode, String arrCode, String depStation, String arrStation, String from, String to, String groupBy,
                              int onTimeThresholdMin, Summary summary, List<PunctualityItem> items,
                              List<Bucket> histogram, Summary nationwideExact, Map<String, String> rules, String note,
                              boolean timetablePending) {}
}
