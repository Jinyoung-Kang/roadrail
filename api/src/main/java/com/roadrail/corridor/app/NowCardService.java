package com.roadrail.corridor.app;

import com.roadrail.corridor.data.CorridorRepository;
import com.roadrail.env.app.EnvService;
import com.roadrail.env.app.HolidayService;
import com.roadrail.rail.app.RailService;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.shared.JsonCache;
import com.roadrail.shared.Times;
import com.roadrail.shared.AppProperties;
import com.roadrail.domain.DecisionRule;
import com.roadrail.domain.ForecastModels;
import com.roadrail.env.model.EnvDtos;
import com.roadrail.corridor.model.NowDtos.*;
import com.roadrail.rail.model.RailDtos;
import com.roadrail.corridor.model.RoadDtos.Latest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/** 판단 카드 (FR-501~504) — 도로·철도·환경·돌발을 모아 R-DEC-01 로 요약. Redis 60초 캐시 (NFR-03). */
@Service
public class NowCardService {
    private final CorridorRepository corridors;
    private final AppProperties props;
    private final RoadService road;
    private final RailService rail;
    private final HolidayService holidays;
    private final EnvService env;
    private final KakaoMobilityClient kakao;
    private final JsonCache cache;

    public NowCardService(CorridorRepository corridors, AppProperties props, RoadService road, RailService rail, EnvService env,
                          KakaoMobilityClient kakao, JsonCache cache, HolidayService holidays) {
        this.corridors = corridors;
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
                () -> build(cid, dir, departIn, accessMin, carAccessMin), c -> cacheable(c, kakao.enabled()));
        return hit.value().withCache(hit.cached() ? "HIT" : "MISS");
    }

    /**
     * 카카오를 쓰는데 경로 예측이 아직 없으면(조회 대기 600ms 를 넘김) 캐시하지 않는다 — 예전에는 '—'인 카드를 60초 동안 돌려줬다(RVW-06).
     * 카카오 결과는 자체 캐시(20분)가 있어 다음 요청에서 바로 채워진다.
     */
    static boolean cacheable(NowCard card, boolean kakaoEnabled) {
        return !kakaoEnabled || card.road() == null || card.road().kakao() != null;
    }

    NowCard build(String cid, String dir, int departIn, int accessMin, int carAccessMin) {
        OffsetDateTime now = Times.now();
        OffsetDateTime depart = Times.alignTo5Min(now).plusMinutes(departIn);
        String name = corridors.name(cid);

        // ---- 도로
        var ends = corridors.roadEnds(cid, dir);
        Optional<Latest> latest = road.latest(cid, dir);
        Map<ForecastModels.Key, ForecastModels.Value> bl = road.baselineMap(cid, dir);
        Kakao kk = kakao.futureEta(ends.fromLat(), ends.fromLon(), ends.toLat(), ends.toLon(), depart)
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
                    kk, ends.fromName(), ends.toName(), Math.round(ends.distanceKm() * 10) / 10.0);
            car = new DecisionRule.Car(pred, model, pct, carAccessMin, "관측 " + Times.ago(l.slotTs(), now));
        } else {
            status = "STALE_DATA";
            roadCard = new Road(null, null, null, null, null, null, depart, 0, null, null, List.of(), kk,
                    ends.fromName(), ends.toName(), Math.round(ends.distanceKm() * 10) / 10.0);
            if (kk != null) car = new DecisionRule.Car(kk.durationSec(), "카카오 미래 운행 정보", null, carAccessMin, "");
        }

        // ---- 철도
        var names = corridors.railStationNames(cid, dir);
        var pair = rail.pairOf(cid, dir);
        RailDtos.NextTrains next = rail.nextTrains(pair.dep(), pair.arr(), depart.plusMinutes(accessMin), 3);
        Rail railCard = new Rail(names.dep(), names.arr(), next.referenceDate(), next.basis(), next.trains());
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
        var pts = corridors.envPoints(cid);
        for (var p : pts) {
            // 상행(UP)은 도착 도시가 출발지 — 역할을 방향에 맞춰 바꾼다
            String role = "DN".equals(dir) ? p.role() : ("origin".equals(p.role()) ? "dest" : "origin");
            Optional<EnvDtos.WeatherHour> w = env.at(p.nx(), p.ny(), depart);
            EnvDtos.Air a = env.air(p.sido());
            envMap.put(role, new PointEnv(p.name(), w.map(EnvDtos.WeatherHour::pop).orElse(null),
                    w.map(EnvDtos.WeatherHour::pty).orElse(null), w.map(EnvDtos.WeatherHour::tmp).orElse(null),
                    w.map(EnvDtos.WeatherHour::sky).orElse(null), a == null ? null : a.pm25(),
                    a == null ? null : a.pm25Grade(), a == null ? null : a.khaiGrade(), null, w.isPresent() ? "단기예보" : null));
            if (a != null && a.dataTime() != null) airTime = a.dataTime();
        }
        weatherBase = corridors.latestWeatherBase().orElse(null);
        var o = envMap.get("origin");
        var d = envMap.get("dest");
        DecisionRule.Env denv = new DecisionRule.Env(o == null ? null : o.pop(), d == null ? null : d.pop(),
                o == null ? null : o.pm25Grade(), d == null ? null : d.pm25Grade(),
                holidays.name(Times.kst(depart).toLocalDate()).orElse(null), o == null ? null : o.pty(), d == null ? null : d.pty());

        // ---- 돌발
        List<EnvDtos.Incident> inc = env.incidents(cid, now.minusHours(6), 5).items();

        var decision = DecisionRule.decide(car, train, denv, inc.size(),
                new DecisionRule.Params(props.decisionSimilarMin(), props.decisionPopWarn(), accessMin));

        Map<String, String> fresh = new LinkedHashMap<>();
        fresh.put("road", latest.map(l -> Times.ago(l.slotTs(), now) + " 슬롯 (도로공사 공개 지연)").orElse("—"));
        fresh.put("rail", next.referenceDate() == null ? "—" : next.referenceDate() + " 운행 기준 시간표");  // 출발지→도착지 카드와 같은 표현
        fresh.put("weather", weatherBase == null ? "—" : Times.kst(weatherBase).format(Times.HM) + " 발표");
        fresh.put("air", airTime == null ? "—" : airTime.format(Times.HM) + " 측정");
        fresh.put("kakao", kk == null ? "—" : "출발 " + kk.departAt().substring(8, 10) + ":" + kk.departAt().substring(10) + " 기준 경로 예측");

        String caveat = "역까지의 접근 시간은 입력값 " + accessMin + "분" + (carAccessMin > 0 ? ", IC까지 " + carAccessMin + "분" : "")
                + "을 더했습니다. 자동차 시간은 영업소(TG)→영업소 구간 기준입니다. 참고 정보이며 교통 안내 서비스가 아닙니다.";
        return new NowCard(cid, name, dir, now, depart, accessMin, carAccessMin, status, roadCard, railCard, envMap, inc,
                decision, fresh, caveat, "MISS");
    }
}
