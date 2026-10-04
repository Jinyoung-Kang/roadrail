package com.roadrail.corridor.model;

import com.roadrail.env.model.EnvDtos;
import com.roadrail.rail.model.RailDtos;
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

    /** rain = 1시간 강수량(초단기, 없으면 null) · weatherSource = 날씨 값의 근거 (단기예보 / 초단기예보 HH:MM 발표 / 초단기실황 HH:MM 관측) */
    public record PointEnv(String name, Integer pop, String pty, Integer tmp, String sky, Integer pm25, Integer pm25Grade,
                           Integer khaiGrade, String rain, String weatherSource) {}

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
