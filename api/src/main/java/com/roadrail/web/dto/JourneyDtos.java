package com.roadrail.web.dto;

import com.roadrail.external.TagoSubwayClient;
import com.roadrail.web.dto.RailDtos;

import java.time.OffsetDateTime;
import java.util.List;

/** 기차 여정 (환승 포함) */
public final class JourneyDtos {
    private JourneyDtos() {}

    /** 어디서 → 역 · 역 → 어디로 이동. mode: WALK(도보 추정) · CAR(카카오 실제 경로) · INPUT(사용자 입력) · ESTIMATE(직선거리 추정) */
    public record Transfer(String stationCode, String stationName, double lat, double lon, double straightKm, int minutes,
                           Integer distanceM, String mode) {}

    /** grade = 기준일 TAGO 시간표의 배정 차종(없으면 null) · timetable = 출발·도착 시각이 실제 시간표(운행계획 · TAGO) 값인지 */
    public record Leg(String trnNo, String fromCode, String fromName, String toCode, String toName, OffsetDateTime dep,
                      OffsetDateTime arr, int rideMin, Double onTimeRate30d, Double avgArrDelayMin30d, int samples,
                      Double fromLat, Double fromLon, Double toLat, Double toLon,
                      RailDtos.TrainMeta meta, List<double[]> path, boolean pathOnTrack, String grade, boolean timetable) {}

    public record Journey(Transfer access, List<Leg> legs, Transfer egress, OffsetDateTime departAt, OffsetDateTime arriveAt,
                          int waitMin, int transfers, int totalMin, Double expectedDelayMin) {}

    public record Plan(List<Journey> journeys, String referenceDate, String basis, int originCandidates,
                       int destCandidates, int boardingBufferMin, int transferMin, String note,
                       List<TagoSubwayClient.NextSubway> subwayAtDeparture, List<TagoSubwayClient.NextSubway> subwayAtArrival,
                       boolean pending) {}
}
