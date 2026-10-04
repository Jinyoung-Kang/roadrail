package com.roadrail.corridor.data;

import com.roadrail.corridor.model.RoadDtos.BacktestCell;
import com.roadrail.corridor.model.RoadDtos.BaselineCell;
import com.roadrail.corridor.model.RoadDtos.EtaPoint;
import com.roadrail.corridor.model.RoadDtos.Latest;
import com.roadrail.domain.ForecastModels;
import com.roadrail.shared.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * 길의 도로 실측 · 분석 조회 — 5분 통행시간 · 평소 기준선 · 예측 평가 · 카카오 예측 기록 · 출발지 강수 예보.
 * 쿼리와 행 매핑만 맡고, 기준선 비교 · 예측 · 통계는 corridor.app 이 한다.
 */
@Repository
public class RoadRepository {
    private final JdbcClient jdbc;

    public RoadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 최근 3일 안의 마지막 관측 슬롯 */
    public Optional<Latest> latest(String cid, String dir) {
        return jdbc.sql("""
                SELECT slot_ts, travel_sec, quality, observed_segs, total_segs FROM ts.road_corridor_tt
                WHERE corridor_id = :c AND direction = :d AND slot_ts > now() - interval '3 days'
                ORDER BY slot_ts DESC LIMIT 1""").param("c", cid).param("d", dir)
                .query((rs, i) -> new Latest(Times.kst(rs.getObject(1, OffsetDateTime.class)), rs.getInt(2),
                        rs.getString(3), rs.getInt(4), rs.getInt(5))).optional();
    }

    /** 기준선 (요일 · 5분 슬롯 → p50 · 표본 수) */
    public Map<ForecastModels.Key, ForecastModels.Value> baselineMap(String cid, String dir) {
        Map<ForecastModels.Key, ForecastModels.Value> m = new HashMap<>();
        jdbc.sql("SELECT dow, slot_idx, p50_sec, n FROM ana.road_baseline WHERE corridor_id = :c AND direction = :d")
                .param("c", cid).param("d", dir).query(rs -> {
                    m.put(new ForecastModels.Key(rs.getInt(1), rs.getInt(2)), new ForecastModels.Value(rs.getInt(3), rs.getInt(4)));
                });
        return m;
    }

    public List<BaselineCell> baselineCells(String cid, String dir) {
        return jdbc.sql("""
                SELECT dow, slot_idx, p50_sec, p90_sec, n FROM ana.road_baseline
                WHERE corridor_id = :c AND direction = :d ORDER BY dow, slot_idx""").param("c", cid).param("d", dir)
                .query((rs, i) -> new BaselineCell(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5))).list();
    }

    /** 기준선의 입력 기간 · 계산 시각 (기준선이 없으면 모두 null) */
    public record BaselineMeta(String windowFrom, String windowTo, OffsetDateTime computedAt) {}

    public BaselineMeta baselineMeta(String cid, String dir) {
        return jdbc.sql("""
                SELECT min(window_from)::text, max(window_to)::text, max(computed_at) FROM ana.road_baseline
                WHERE corridor_id = :c AND direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new BaselineMeta(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class))).single();
    }

    /** 통행시간 한 점 — 시각(KST) · 통행시간 · 품질 · 관측 구간 비율(반올림 전) */
    public record SeriesRow(OffsetDateTime t, int travelSec, String quality, double coverage) {}

    /** 기간의 통행시간 — fiveMinute 이면 5분 슬롯 그대로, 아니면 1시간 평균 (시각 순) */
    public List<SeriesRow> series(String cid, String dir, OffsetDateTime from, OffsetDateTime to, boolean fiveMinute) {
        String sql = fiveMinute ? """
                SELECT slot_ts AS t, travel_sec AS v, quality, observed_segs::float / total_segs AS cov
                FROM ts.road_corridor_tt WHERE corridor_id = :c AND direction = :d AND slot_ts >= :f AND slot_ts < :t
                ORDER BY slot_ts""" : """
                SELECT date_trunc('hour', slot_ts) AS t, round(avg(travel_sec))::int AS v,
                       CASE WHEN bool_and(quality = 'OK') THEN 'OK' ELSE 'FILLED' END AS quality,
                       count(*)::float / 12 AS cov
                FROM ts.road_corridor_tt WHERE corridor_id = :c AND direction = :d AND slot_ts >= :f AND slot_ts < :t
                GROUP BY 1 ORDER BY 1""";
        return jdbc.sql(sql).param("c", cid).param("d", dir).param("f", from).param("t", to)
                .query((rs, i) -> new SeriesRow(Times.kst(rs.getObject("t", OffsetDateTime.class)), rs.getInt("v"),
                        rs.getString("quality"), rs.getDouble("cov"))).list();
    }

    /** 예보 시각 하나의 강수 원값 — 시각(KST) · 강수확률(POP) · 강수형태(PTY) 코드 */
    public record RainRow(OffsetDateTime t, String pop, String pty) {}

    /** 출발지 격자의 시간별 강수 (각 예보 시각에 대해 가장 최근 발표값, 시각 순) */
    public List<RainRow> rain(String cid, String dir, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                WITH g AS (SELECT nx, ny FROM ref.corridor_env_point WHERE corridor_id = :c
                           AND role = CASE WHEN :d = 'DN' THEN 'origin' ELSE 'dest' END)
                SELECT DISTINCT ON (w.fcst_at) w.fcst_at,
                       max(w.value) FILTER (WHERE w.category = 'POP') OVER (PARTITION BY w.fcst_at, w.base_at) AS pop,
                       max(w.value) FILTER (WHERE w.category = 'PTY') OVER (PARTITION BY w.fcst_at, w.base_at) AS pty
                FROM env.weather_fcst w JOIN g ON w.nx = g.nx AND w.ny = g.ny
                WHERE w.fcst_at >= :f AND w.fcst_at < :t AND w.category IN ('POP', 'PTY')
                ORDER BY w.fcst_at, w.base_at DESC""").param("c", cid).param("d", dir).param("f", from).param("t", to)
                .query((rs, i) -> new RainRow(Times.kst(rs.getObject(1, OffsetDateTime.class)),
                        rs.getString(2), rs.getString(3))).list();
    }

    /** 출발 시각별 카카오 미래 운행 예측 (출발 시각마다 가장 최근 요청, 시각 순) */
    public List<EtaPoint> kakaoEta(String cid, String dir, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                SELECT DISTINCT ON (depart_at) depart_at, duration_sec FROM ana.kakao_eta
                WHERE corridor_id = :c AND direction = :d AND depart_at >= :f AND depart_at < :t
                ORDER BY depart_at, requested_at DESC""").param("c", cid).param("d", dir).param("f", from).param("t", to)
                .query((rs, i) -> new EtaPoint(Times.kst(rs.getObject(1, OffsetDateTime.class)), rs.getInt(2))).list();
    }

    /** 가장 최근 평가일의 horizon · 모델별 예측 오차 */
    public List<BacktestCell> backtestCells(String cid, String dir) {
        return jdbc.sql("""
                SELECT horizon_min, model, mae_sec::float, mape::float, n FROM ana.forecast_eval
                WHERE corridor_id = :c AND direction = :d
                  AND eval_date = (SELECT max(eval_date) FROM ana.forecast_eval WHERE corridor_id = :c AND direction = :d)
                ORDER BY horizon_min, model""").param("c", cid).param("d", dir)
                .query((rs, i) -> new BacktestCell(rs.getInt(1), rs.getString(2), (Double) rs.getObject(3),
                        (Double) rs.getObject(4), rs.getInt(5))).list();
    }

    /** 예측 평가의 최근 평가일 · 모델 버전 · 평가 기간 (평가가 없으면 모두 null) */
    public record BacktestMeta(String evalDate, String modelVersion, OffsetDateTime windowFrom, OffsetDateTime windowTo) {}

    public BacktestMeta backtestMeta(String cid, String dir) {
        return jdbc.sql("""
                SELECT max(eval_date)::text, max(model_version), min(window_from), max(window_to) FROM ana.forecast_eval
                WHERE corridor_id = :c AND direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new BacktestMeta(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                        rs.getObject(4, OffsetDateTime.class))).single();
    }
}
