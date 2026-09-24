package com.roadrail.service;

import com.roadrail.common.ApiException;
import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.domain.ForecastModels;
import com.roadrail.web.dto.RoadDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class RoadService {
    private final JdbcClient jdbc;
    private final AppProperties props;

    public RoadService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public Optional<Latest> latest(String cid, String dir) {
        return jdbc.sql("""
                SELECT slot_ts, travel_sec, quality, observed_segs, total_segs FROM ts.road_corridor_tt
                WHERE corridor_id = :c AND direction = :d AND slot_ts > now() - interval '3 days'
                ORDER BY slot_ts DESC LIMIT 1""").param("c", cid).param("d", dir)
                .query((rs, i) -> new Latest(Times.kst(rs.getObject(1, OffsetDateTime.class)), rs.getInt(2),
                        rs.getString(3), rs.getInt(4), rs.getInt(5))).optional();
    }

    public Map<ForecastModels.Key, ForecastModels.Value> baselineMap(String cid, String dir) {
        Map<ForecastModels.Key, ForecastModels.Value> m = new HashMap<>();
        jdbc.sql("SELECT dow, slot_idx, p50_sec, n FROM ana.road_baseline WHERE corridor_id = :c AND direction = :d")
                .param("c", cid).param("d", dir).query(rs -> {
                    m.put(new ForecastModels.Key(rs.getInt(1), rs.getInt(2)), new ForecastModels.Value(rs.getInt(3), rs.getInt(4)));
                });
        return m;
    }

    public Baseline baseline(String cid, String dir) {
        List<BaselineCell> cells = jdbc.sql("""
                SELECT dow, slot_idx, p50_sec, p90_sec, n FROM ana.road_baseline
                WHERE corridor_id = :c AND direction = :d ORDER BY dow, slot_idx""").param("c", cid).param("d", dir)
                .query((rs, i) -> new BaselineCell(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5))).list();
        var meta = jdbc.sql("""
                SELECT min(window_from)::text, max(window_to)::text, max(computed_at) FROM ana.road_baseline
                WHERE corridor_id = :c AND direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class)}).single();
        return new Baseline(cid, dir, (String) meta[0], (String) meta[1], Times.kst((OffsetDateTime) meta[2]), cells,
                "최근 8주 같은 요일·5분 슬롯의 통행시간 p50·p90. dow 0 = 전체 요일(표본 n<4 일 때 대체). 결측 슬롯은 셀 없음. "
                        + "공휴일(한국천문연구원 특일 정보)은 입력에서 뺍니다 — 명절 정체가 평소 기준선을 오염시키지 않게. "
                        + "공휴일 자료만 있으면 기준선은 비어 있습니다.");
    }

    public Series series(String cid, String dir, OffsetDateTime from, OffsetDateTime to, String agg) {
        if (!to.isAfter(from)) throw ApiException.invalid("to 는 from 보다 뒤여야 합니다.");
        if (Duration.between(from, to).toDays() > 92) throw ApiException.invalid("조회 기간은 최대 92일입니다.");
        if (!"5m".equals(agg) && !"1h".equals(agg)) throw ApiException.invalid("agg 는 5m 또는 1h 입니다.");
        Map<ForecastModels.Key, ForecastModels.Value> bl = baselineMap(cid, dir);
        String sql = "5m".equals(agg) ? """
                SELECT slot_ts AS t, travel_sec AS v, quality, observed_segs::float / total_segs AS cov
                FROM ts.road_corridor_tt WHERE corridor_id = :c AND direction = :d AND slot_ts >= :f AND slot_ts < :t
                ORDER BY slot_ts""" : """
                SELECT date_trunc('hour', slot_ts) AS t, round(avg(travel_sec))::int AS v,
                       CASE WHEN bool_and(quality = 'OK') THEN 'OK' ELSE 'FILLED' END AS quality,
                       count(*)::float / 12 AS cov
                FROM ts.road_corridor_tt WHERE corridor_id = :c AND direction = :d AND slot_ts >= :f AND slot_ts < :t
                GROUP BY 1 ORDER BY 1""";
        List<SeriesPoint> pts = jdbc.sql(sql).param("c", cid).param("d", dir).param("f", from).param("t", to)
                .query((rs, i) -> {
                    OffsetDateTime t = Times.kst(rs.getObject("t", OffsetDateTime.class));
                    Integer b = ForecastModels.m0(bl, t);
                    return new SeriesPoint(t, rs.getInt("v"), b, rs.getString("quality"), Math.round(rs.getDouble("cov") * 100) / 100.0);
                }).list();
        // 출발지 격자의 시간별 강수 (각 예보 시각에 대해 가장 최근 발표값) — FR-603 음영용
        List<RainPoint> rain = jdbc.sql("""
                WITH g AS (SELECT nx, ny FROM ref.corridor_env_point WHERE corridor_id = :c
                           AND role = CASE WHEN :d = 'DN' THEN 'origin' ELSE 'dest' END)
                SELECT DISTINCT ON (w.fcst_at) w.fcst_at,
                       max(w.value) FILTER (WHERE w.category = 'POP') OVER (PARTITION BY w.fcst_at, w.base_at) AS pop,
                       max(w.value) FILTER (WHERE w.category = 'PTY') OVER (PARTITION BY w.fcst_at, w.base_at) AS pty
                FROM env.weather_fcst w JOIN g ON w.nx = g.nx AND w.ny = g.ny
                WHERE w.fcst_at >= :f AND w.fcst_at < :t AND w.category IN ('POP', 'PTY')
                ORDER BY w.fcst_at, w.base_at DESC""").param("c", cid).param("d", dir).param("f", from).param("t", to)
                .query((rs, i) -> new RainPoint(Times.kst(rs.getObject(1, OffsetDateTime.class)),
                        parseInt(rs.getString(2)), ptyName(rs.getString(3)))).list();
        List<EtaPoint> eta = jdbc.sql("""
                SELECT DISTINCT ON (depart_at) depart_at, duration_sec FROM ana.kakao_eta
                WHERE corridor_id = :c AND direction = :d AND depart_at >= :f AND depart_at < :t
                ORDER BY depart_at, requested_at DESC""").param("c", cid).param("d", dir).param("f", from).param("t", to)
                .query((rs, i) -> new EtaPoint(Times.kst(rs.getObject(1, OffsetDateTime.class)), rs.getInt(2))).list();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("points", pts.size());
        stats.put("expectedPoints", "5m".equals(agg) ? Duration.between(from, to).toMinutes() / 5 : Duration.between(from, to).toHours());
        stats.put("filledShare", pts.isEmpty() ? null :
                Math.round(pts.stream().filter(p -> !"OK".equals(p.quality())).count() * 1000.0 / pts.size()) / 1000.0);
        return new Series(cid, dir, agg, from, to, pts, rain, eta, stats,
                "길 통행시간 = 영업소 구간 통행시간(1종·도착기준 5분)의 합. 결측 슬롯은 점이 없습니다. "
                        + "강수는 단기예보(관측 아님)이며 상관 관계를 볼 뿐 인과가 아닙니다.");
    }

    public Forecast forecast(String cid, String dir, List<Integer> horizons) {
        OffsetDateTime now = Times.alignTo5Min(Times.now());
        Optional<Latest> latest = latest(cid, dir);
        Map<ForecastModels.Key, ForecastModels.Value> bl = baselineMap(cid, dir);
        List<ForecastItem> items = new ArrayList<>();
        if (latest.isPresent()) {
            Latest l = latest.get();
            for (int h : horizons) {
                OffsetDateTime target = now.plusMinutes(h);
                var f = ForecastModels.predictAll(bl, l.slotTs(), l.travelSec(), target, props.forecastTauMin());
                items.add(new ForecastItem(h, (int) Duration.between(l.slotTs(), target).toMinutes(), target,
                        f.m0(), f.m1(), f.persistence(), ForecastModels.m0N(bl, target)));
            }
        }
        return new Forecast(cid, dir, now, latest.map(Latest::slotTs).orElse(null), latest.map(Latest::travelSec).orElse(null),
                items, backtest(cid, dir),
                "도로공사 통행시간은 공개가 늦으므로(수 시간) 예측의 실제 선행시간 leadMin = 목표 시각 − 마지막 관측 슬롯. "
                        + "backtest 는 데이터가 쌓이기 전까지 비어 있을 수 있습니다.");
    }

    public Backtest backtest(String cid, String dir) {
        List<BacktestCell> cells = jdbc.sql("""
                SELECT horizon_min, model, mae_sec::float, mape::float, n FROM ana.forecast_eval
                WHERE corridor_id = :c AND direction = :d
                  AND eval_date = (SELECT max(eval_date) FROM ana.forecast_eval WHERE corridor_id = :c AND direction = :d)
                ORDER BY horizon_min, model""").param("c", cid).param("d", dir)
                .query((rs, i) -> new BacktestCell(rs.getInt(1), rs.getString(2), (Double) rs.getObject(3),
                        (Double) rs.getObject(4), rs.getInt(5))).list();
        var meta = jdbc.sql("""
                SELECT max(eval_date)::text, max(model_version), min(window_from), max(window_to) FROM ana.forecast_eval
                WHERE corridor_id = :c AND direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                        rs.getObject(4, OffsetDateTime.class)}).single();
        Map<String, Double> mae = new LinkedHashMap<>();
        for (String m : List.of("M0", "M1", "persistence")) {
            // horizon 전체 가중 평균 MAE (표본 수 가중)
            double sum = 0; int n = 0;
            for (BacktestCell c : cells) if (c.model().equals(m) && c.maeSec() != null) { sum += c.maeSec() * c.n(); n += c.n(); }
            mae.put(m, n == 0 ? null : Math.round(sum / n * 10) / 10.0);
        }
        String period = meta[2] == null ? "최근 28일" : String.format("%s ~ %s",
                Times.kst((OffsetDateTime) meta[2]).toLocalDate(), Times.kst((OffsetDateTime) meta[3]).toLocalDate().minusDays(1));
        return new Backtest(period, (String) meta[0], (String) meta[1], mae, cells);
    }

    static Integer parseInt(String s) {
        try { return s == null ? null : Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** 기상청 PTY 코드 → 이름 (0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기) */
    public static String ptyName(String code) {
        if (code == null) return null;
        return switch (code.trim()) {
            case "0" -> "없음";
            case "1" -> "비";
            case "2" -> "비/눈";
            case "3" -> "눈";
            case "4" -> "소나기";
            default -> code;
        };
    }
}
