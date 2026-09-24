package com.roadrail.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class RoadDtos {
    private RoadDtos() {}

    public record Latest(OffsetDateTime slotTs, int travelSec, String quality, int observedSegs, int totalSegs) {}

    public record SeriesPoint(OffsetDateTime t, Integer travelSec, Integer baselineP50Sec, String quality, Double coverage) {}

    public record RainPoint(OffsetDateTime t, Integer pop, String pty) {}

    public record EtaPoint(OffsetDateTime departAt, int durationSec) {}

    public record Series(String corridorId, String direction, String agg, OffsetDateTime from, OffsetDateTime to,
                         List<SeriesPoint> points, List<RainPoint> rain, List<EtaPoint> kakaoEta,
                         Map<String, Object> stats, String note) {}

    public record BaselineCell(int dow, int slotIdx, int p50Sec, int p90Sec, int n) {}

    public record Baseline(String corridorId, String direction, String windowFrom, String windowTo,
                           OffsetDateTime computedAt, List<BaselineCell> cells, String note) {}

    public record ForecastItem(int horizonMin, int leadMin, OffsetDateTime targetAt, Integer M0, Integer M1, int persistence,
                               int baselineN) {}

    public record BacktestCell(int horizonMin, String model, Double maeSec, Double mape, int n) {}

    public record Backtest(String period, String evalDate, String modelVersion, Map<String, Double> maeSec,
                           List<BacktestCell> cells) {}

    public record Forecast(String corridorId, String direction, OffsetDateTime issuedAt, OffsetDateTime lastObservedSlot,
                           Integer lastObservedSec, List<ForecastItem> items, Backtest backtest, String note) {}
}
