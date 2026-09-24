package com.roadrail.service;

import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.domain.DecisionRule;
import com.roadrail.domain.ForecastModels;
import com.roadrail.web.dto.EnvDtos;
import com.roadrail.web.dto.NowDtos.*;
import com.roadrail.web.dto.RailDtos;
import com.roadrail.web.dto.RoadDtos.Latest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/** 판단 카드 (FR-501~504) — 도로·철도·환경·돌발을 모아 R-DEC-01 로 요약. Redis 60초 캐시 (NFR-03). */
@Service
public class NowCardService {
    private final JdbcClient jdbc;
    private final AppProperties props;
    private final RoadService road;
    private final RailService rail;
    private final HolidayService holidays;
    private final EnvService env;
    private final KakaoMobilityClient kakao;
    private final JsonCache cache;

    public NowCardService(JdbcClient jdbc, AppProperties props, RoadService road, RailService rail, EnvService env,
                          KakaoMobilityClient kakao, JsonCache cache, HolidayService holidays) {
        this.jdbc = jdbc;
        this.props = props;
        this.road = road;
        this.rail = rail;
        this.holidays = holidays;
        this.env = env;
        this.kakao = kakao;
        this.cache = cache;
    }

    public NowCard now(String cid, String dir, int departIn, int accessMin, int carAccessMin) {
        String key = "now:%s:%s:%d:%d:%d".formatted(cid, dir, departIn, accessMin, carAccessMin);
        var hit = cache.get(key, Duration.ofSeconds(props.nowCacheSeconds()), NowCard.class,
                () -> build(cid, dir, departIn, accessMin, carAccessMin));
        return hit.value().withCache(hit.cached() ? "HIT" : "MISS");
    }

    NowCard build(String cid, String dir, int departIn, int accessMin, int carAccessMin) {
        OffsetDateTime now = Times.now();
        OffsetDateTime depart = Times.alignTo5Min(now).plusMinutes(departIn);
        String name = jdbc.sql("SELECT name FROM ref.corridor WHERE corridor_id = :c").param("c", cid).query(String.class).single();

        // ---- 도로
        var ends = jdbc.sql("""
                SELECT (array_agg(us.unit_name ORDER BY r.seq))[1], (array_agg(ue.unit_name ORDER BY r.seq DESC))[1],
                       (array_agg(us.lat ORDER BY r.seq))[1], (array_agg(us.lon ORDER BY r.seq))[1],
                       (array_agg(ue.lat ORDER BY r.seq DESC))[1], (array_agg(ue.lon ORDER BY r.seq DESC))[1],
                       sum(r.distance_km)::float
                FROM ref.corridor_road r JOIN ref.toll_unit us ON us.unit_code = r.start_unit_code
                JOIN ref.toll_unit ue ON ue.unit_code = r.end_unit_code
                WHERE r.corridor_id = :c AND r.direction = :d""").param("c", cid).param("d", dir)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getDouble(7)}).single();
        Optional<Latest> latest = road.latest(cid, dir);
        Map<ForecastModels.Key, ForecastModels.Value> bl = road.baselineMap(cid, dir);
        Kakao kk = kakao.futureEta((double) ends[2], (double) ends[3], (double) ends[4], (double) ends[5], depart)
                .map(e -> new Kakao(e.durationSec(), e.distanceM(), e.departAt())).orElse(null);
        Road roadCard;
        String status = "OK";
        DecisionRule.Car car = null;
        if (latest.isPresent()) {
            Latest l = latest.get();
            // 편차 %는 표본 n ≥ 4 인 기준선에서만 (FR-402 — 아니면 '비교 불가')
            ForecastModels.Value cmp = ForecastModels.comparable(bl, l.slotTs());
            Integer b = cmp == null ? null : cmp.p50();
            Double pct = b == null || b == 0 ? null : Math.round((l.travelSec() - b) * 1000.0 / b) / 10.0;
            var f = ForecastModels.predictAll(bl, l.slotTs(), l.travelSec(), depart, props.forecastTauMin());
            String model = f.m1() != null ? "M1" : f.m0() != null ? "M0" : "persistence";
            Integer pred = f.m1() != null ? f.m1() : f.m0() != null ? f.m0() : Integer.valueOf(f.persistence());
            int lead = (int) Duration.between(l.slotTs(), depart).toMinutes();
            if (Duration.between(l.slotTs(), now).toMinutes() > props.roadStaleMinutes()) status = "STALE_DATA";
            roadCard = new Road(l.travelSec(), b, pct, l.slotTs(), l.quality(),
                    Math.round(l.observedSegs() * 100.0 / l.totalSegs()) / 100.0, depart, lead, pred, model,
                    List.of(new ForecastPoint("M0", f.m0()), new ForecastPoint("M1", f.m1()),
                            new ForecastPoint("persistence", f.persistence())),
                    kk, (String) ends[0], (String) ends[1], Math.round((double) ends[6] * 10) / 10.0);
            car = new DecisionRule.Car(pred, model, pct, carAccessMin, "관측 " + Times.ago(l.slotTs(), now));
        } else {
            status = "STALE_DATA";
            roadCard = new Road(null, null, null, null, null, null, depart, 0, null, null, List.of(), kk,
                    (String) ends[0], (String) ends[1], Math.round((double) ends[6] * 10) / 10.0);
            if (kk != null) car = new DecisionRule.Car(kk.durationSec(), "카카오 미래 운행 정보", null, carAccessMin, "");
        }

        // ---- 철도
        var names = jdbc.sql("""
                SELECT sd.stn_nm, sa.stn_nm FROM ref.corridor_rail cr JOIN ref.station sd ON sd.stn_cd = cr.dep_stn_cd
                JOIN ref.station sa ON sa.stn_cd = cr.arr_stn_cd WHERE cr.corridor_id = :c AND cr.direction = :d""")
                .param("c", cid).param("d", dir).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        var pair = rail.pairOf(cid, dir);
        RailDtos.NextTrains next = rail.nextTrains(pair.dep(), pair.arr(), depart.plusMinutes(accessMin), 3);
        Rail railCard = new Rail(names[0], names[1], next.referenceDate(), next.basis(), next.trains());
        DecisionRule.Train train = null;
        if (!next.trains().isEmpty()) {
            var t = next.trains().getFirst();
            int wait = (int) Math.max(Duration.between(depart.plusMinutes(accessMin), t.planDepAt()).toMinutes(), 0);
            train = new DecisionRule.Train(t.trnNo(), t.planDep(), wait, t.planRideMin(), t.avgArrDelayMin30d(),
                    t.onTimeRate30d(), t.samples());
        }

        // ---- 환경
        Map<String, PointEnv> envMap = new LinkedHashMap<>();
        OffsetDateTime weatherBase = null, airTime = null;
        var pts = jdbc.sql("""
                SELECT role, name, nx, ny, sido_name FROM ref.corridor_env_point WHERE corridor_id = :c""")
                .param("c", cid).query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getString(5)}).list();
        for (Object[] p : pts) {
            // 상행(UP)은 도착 도시가 출발지 — 역할을 방향에 맞춰 바꾼다
            String role = "DN".equals(dir) ? (String) p[0] : ("origin".equals(p[0]) ? "dest" : "origin");
            Optional<EnvDtos.WeatherHour> w = env.at((int) p[2], (int) p[3], depart);
            EnvDtos.Air a = env.air((String) p[4]);
            envMap.put(role, new PointEnv((String) p[1], w.map(EnvDtos.WeatherHour::pop).orElse(null),
                    w.map(EnvDtos.WeatherHour::pty).orElse(null), w.map(EnvDtos.WeatherHour::tmp).orElse(null),
                    w.map(EnvDtos.WeatherHour::sky).orElse(null), a == null ? null : a.pm25(),
                    a == null ? null : a.pm25Grade(), a == null ? null : a.khaiGrade()));
            if (a != null && a.dataTime() != null) airTime = a.dataTime();
        }
        weatherBase = jdbc.sql("SELECT max(base_at) FROM env.weather_fcst").query(OffsetDateTime.class).optional().orElse(null);
        var o = envMap.get("origin");
        var d = envMap.get("dest");
        DecisionRule.Env denv = new DecisionRule.Env(o == null ? null : o.pop(), d == null ? null : d.pop(),
                o == null ? null : o.pm25Grade(), d == null ? null : d.pm25Grade(),
                holidays.name(Times.kst(depart).toLocalDate()).orElse(null));

        // ---- 돌발
        List<EnvDtos.Incident> inc = env.incidents(cid, now.minusHours(6), 5).items();

        var decision = DecisionRule.decide(car, train, denv, inc.size(),
                new DecisionRule.Params(props.decisionSimilarMin(), props.decisionPopWarn(), accessMin));

        Map<String, String> fresh = new LinkedHashMap<>();
        fresh.put("road", latest.map(l -> Times.ago(l.slotTs(), now) + " 슬롯 (도로공사 공개 지연)").orElse("—"));
        fresh.put("rail", next.referenceDate() == null ? "—" : next.referenceDate() + " 운행 기준 · 매일 03:30 계산");
        fresh.put("weather", weatherBase == null ? "—" : Times.kst(weatherBase).format(Times.HM) + " 발표");
        fresh.put("air", airTime == null ? "—" : airTime.format(Times.HM) + " 측정");
        fresh.put("kakao", kk == null ? "—" : "출발 " + kk.departAt().substring(8, 10) + ":" + kk.departAt().substring(10) + " 기준 경로 예측");

        String caveat = "역까지의 접근 시간은 입력값 " + accessMin + "분" + (carAccessMin > 0 ? ", IC까지 " + carAccessMin + "분" : "")
                + "을 더했습니다. 자동차 시간은 영업소(TG)→영업소 구간 기준입니다. 참고 정보이며 교통 안내 서비스가 아닙니다.";
        return new NowCard(cid, name, dir, now, depart, accessMin, carAccessMin, status, roadCard, railCard, envMap, inc,
                decision, fresh, caveat, "MISS");
    }
}
