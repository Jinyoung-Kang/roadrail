package com.roadrail.corridor.app;

import com.roadrail.domain.WeatherCodes;
import com.roadrail.shared.ApiException;
import com.roadrail.shared.Times;
import com.roadrail.shared.AppProperties;
import com.roadrail.domain.ForecastModels;
import com.roadrail.corridor.data.RoadRepository;
import com.roadrail.corridor.model.RoadDtos.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class RoadService {
    private final RoadRepository repo;
    private final AppProperties props;

    public RoadService(RoadRepository repo, AppProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public Optional<Latest> latest(String cid, String dir) {
        return repo.latest(cid, dir);
    }

    public Map<ForecastModels.Key, ForecastModels.Value> baselineMap(String cid, String dir) {
        return repo.baselineMap(cid, dir);
    }

    public Baseline baseline(String cid, String dir) {
        List<BaselineCell> cells = repo.baselineCells(cid, dir);
        var meta = repo.baselineMeta(cid, dir);
        return new Baseline(cid, dir, meta.windowFrom(), meta.windowTo(), Times.kst(meta.computedAt()), cells,
                "최근 8주 같은 요일·5분 슬롯의 통행시간 p50·p90. dow 0 = 전체 요일(표본 n<4 일 때 대체). 결측 슬롯은 셀 없음. "
                        + "공휴일(한국천문연구원 특일 정보)은 입력에서 뺍니다 — 명절 정체가 평소 기준선을 오염시키지 않게. "
                        + "공휴일 자료만 있으면 기준선은 비어 있습니다.");
    }

    public Series series(String cid, String dir, OffsetDateTime from, OffsetDateTime to, String agg) {
        if (!to.isAfter(from)) throw ApiException.invalid("to 는 from 보다 뒤여야 합니다.");
        if (Duration.between(from, to).toDays() > 92) throw ApiException.invalid("조회 기간은 최대 92일입니다.");
        if (!"5m".equals(agg) && !"1h".equals(agg)) throw ApiException.invalid("agg 는 5m 또는 1h 입니다.");
        Map<ForecastModels.Key, ForecastModels.Value> bl = baselineMap(cid, dir);
        List<SeriesPoint> pts = new ArrayList<>();
        for (var r : repo.series(cid, dir, from, to, "5m".equals(agg))) {
            Integer b = ForecastModels.m0(bl, r.t());
            pts.add(new SeriesPoint(r.t(), r.travelSec(), b, r.quality(), Math.round(r.coverage() * 100) / 100.0));
        }
        // 출발지 격자의 시간별 강수 (각 예보 시각에 대해 가장 최근 발표값) — FR-603 음영용
        List<RainPoint> rain = new ArrayList<>();
        for (var r : repo.rain(cid, dir, from, to)) {
            rain.add(new RainPoint(r.t(), WeatherCodes.parseInt(r.pop()), WeatherCodes.ptyName(r.pty())));
        }
        List<EtaPoint> eta = repo.kakaoEta(cid, dir, from, to);
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
                "도로공사 통행시간은 몇 시간 늦게 공개되어, 실제로 예측하는 거리는 목표 시각에서 마지막 관측 시각을 뺀 만큼입니다. "
                        + "지난 기록 채점은 기록이 쌓이기 전까지 비어 있을 수 있습니다.");
    }

    public Backtest backtest(String cid, String dir) {
        List<BacktestCell> cells = repo.backtestCells(cid, dir);
        var meta = repo.backtestMeta(cid, dir);
        Map<String, Double> mae = new LinkedHashMap<>();
        for (String m : List.of("M0", "M1", "persistence")) {
            // horizon 전체 가중 평균 MAE (표본 수 가중)
            double sum = 0; int n = 0;
            for (BacktestCell c : cells) if (c.model().equals(m) && c.maeSec() != null) { sum += c.maeSec() * c.n(); n += c.n(); }
            mae.put(m, n == 0 ? null : Math.round(sum / n * 10) / 10.0);
        }
        String period = meta.windowFrom() == null ? "최근 28일" : String.format("%s ~ %s",
                Times.kst(meta.windowFrom()).toLocalDate(), Times.kst(meta.windowTo()).toLocalDate().minusDays(1));
        return new Backtest(period, meta.evalDate(), meta.modelVersion(), mae, cells);
    }
}
