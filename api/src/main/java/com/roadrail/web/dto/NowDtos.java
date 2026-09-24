package com.roadrail.web.dto;

import com.roadrail.domain.DecisionRule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class NowDtos {
    private NowDtos() {}

    public record ForecastPoint(String model, Integer travelSec) {}

    public record Kakao(int durationSec, int distanceM, String departAt) {}

    public record Road(Integer travelSec, Integer baselineP50Sec, Double vsBaselinePct, OffsetDateTime slotTs,
                       String quality, Double coverage, OffsetDateTime targetAt, int leadMin, Integer predictedSec,
                       String model, List<ForecastPoint> forecast, Kakao kakao, String from, String to, double distanceKm) {}

    public record Rail(String depStation, String arrStation, String referenceDate, String basis,
                       List<RailDtos.NextTrain> nextTrains) {}

    public record PointEnv(String name, Integer pop, String pty, Integer tmp, String sky, Integer pm25, Integer pm25Grade,
                           Integer khaiGrade) {}

    public record NowCard(String corridorId, String corridorName, String direction, OffsetDateTime asOf,
                          OffsetDateTime departAt, int accessMin, int carAccessMin, String status, Road road, Rail rail,
                          Map<String, PointEnv> env, List<EnvDtos.Incident> incidents, DecisionRule.Result decision,
                          Map<String, String> freshness, String caveat, String cache) {

        public NowCard withCache(String c) {
            return new NowCard(corridorId, corridorName, direction, asOf, departAt, accessMin, carAccessMin, status, road,
                    rail, env, incidents, decision, freshness, caveat, c);
        }
    }
}
