package com.roadrail.service;

import com.roadrail.common.ApiException;
import com.roadrail.common.Times;
import com.roadrail.config.AppProperties;
import com.roadrail.domain.DecisionRule;
import com.roadrail.domain.ForecastModels;
import com.roadrail.domain.KmaGrid;
import com.roadrail.external.AirKoreaClient;
import com.roadrail.external.KakaoLocalClient;
import com.roadrail.external.KmaClient;
import com.roadrail.web.dto.EnvDtos;
import com.roadrail.web.dto.JourneyDtos;
import com.roadrail.web.dto.NowDtos;
import com.roadrail.web.dto.RailDtos;
import com.roadrail.web.dto.RoadDtos.Latest;
import com.roadrail.web.dto.TripDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 어디서 → 어디로 판단 카드 — 전국 임의의 두 지점.
 * <ul>
 *   <li>자동차: 카카오 미래 운행 정보 (출발 시각 기준, 집 앞 → 목적지 경로). 두 지점이 수집 중인 길과 겹치면
 *       고속도로 실측(평소 대비 %)을 근거로 덧붙인다.</li>
 *   <li>기차: 출발·도착 반경 40km 안의 운행 역 최대 4곳씩 조합해 '역까지 + 대기 + 탑승 + 평균 지연 + 역에서'
 *       합이 가장 작은 직통 열차 (환승 제외).</li>
 *   <li>날씨·대기: 수집된 값이 있으면 DB, 없으면 조회 시점 호출 (공유 예산 · 캐시).</li>
 * </ul>
 */
@Service
public class TripService {
    static final double MIN_RAIL_KM = 15;
    static final Duration DEADLINE = Duration.ofMillis(750);
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final JdbcClient jdbc;
    private final AppProperties props;
    private final RailService rail;
    private final RoadService road;
    private final EnvService env;
    private final KakaoMobilityClient mobility;
    private final KakaoLocalClient local;
    private final KmaClient kma;
    private final AirKoreaClient air;
    private final JsonCache cache;
    private final RailJourneyService journeys;
    private final HolidayService holidays;

    public TripService(JdbcClient jdbc, AppProperties props, RailService rail, RoadService road, EnvService env,
                       KakaoMobilityClient mobility, KakaoLocalClient local, KmaClient kma, AirKoreaClient air, JsonCache cache,
                       RailJourneyService journeys, HolidayService holidays) {
        this.jdbc = jdbc;
        this.props = props;
        this.rail = rail;
        this.road = road;
        this.env = env;
        this.mobility = mobility;
        this.local = local;
        this.kma = kma;
        this.air = air;
        this.cache = cache;
        this.journeys = journeys;
        this.holidays = holidays;
    }

    static Duration left(long deadlineNanos) {
        return Duration.ofNanos(Math.max(deadlineNanos - System.nanoTime(), 1_000_000));
    }

    static <T> T join(CompletableFuture<T> f, Duration wait) {
        try {
            return f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        }
    }

    public Trip trip(Place from, Place to, int departIn, Integer accessMin) {
        validate(from);
        validate(to);
        String key = String.format(Locale.ROOT, "trip:%.4f,%.4f:%.4f,%.4f:%d:%s", from.lat(), from.lon(), to.lat(), to.lon(),
                departIn, accessMin == null ? "auto" : accessMin);
        Trip hit = cache.peek(key, Trip.class);
        if (hit != null) return hit.withCache("HIT");
        Trip t = build(from, to, departIn, accessMin);
        if (!t.pending()) cache.put(key, t, Duration.ofSeconds(props.nowCacheSeconds()));  // 외부 조회 대기 중이면 캐시하지 않음
        return t;
    }

    public static void validate(Place p) {
        if (p.lat() < 33 || p.lat() > 39 || p.lon() < 124 || p.lon() > 132) {
            throw ApiException.invalid("대한민국 안의 좌표만 지원합니다: " + p.name());
        }
    }

    Trip build(Place from, Place to, int departIn, Integer accessMin) {
        OffsetDateTime now = Times.now();
        OffsetDateTime depart = Times.alignTo5Min(now).plusMinutes(departIn);
        double km = Math.round(KmaGrid.km(from.lat(), from.lon(), to.lat(), to.lon()) * 10) / 10.0;

        // 자동차 · 기차 · 양쪽 날씨·대기 · 수집 중인 길 매칭을 가상 스레드로 동시에 (응답 예산 NFR-03).
        // 외부 조회가 늦으면 WAIT 까지만 기다리고 비워 둔다 — 조회는 계속되어 캐시를 채우고, 화면은 pending 을 보고 다시 부른다.
        var fOrigin = CompletableFuture.supplyAsync(() -> pointEnv(from, depart), exec);
        var fDest = CompletableFuture.supplyAsync(() -> pointEnv(to, depart), exec);
        var fRail = CompletableFuture.supplyAsync(() -> km < MIN_RAIL_KM ? null : journeys.plan(from, to, depart, accessMin), exec);
        var fObs = CompletableFuture.supplyAsync(() -> observed(from, to, depart, now), exec);

        // ---- 자동차 (카카오) — 모든 외부 대기는 하나의 마감 시각을 공유한다 (순서대로 더해지지 않게)
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        var fCar = CompletableFuture.supplyAsync(
                () -> mobility.futureEta(from.lat(), from.lon(), to.lat(), to.lon(), depart, true), exec);
        var eta = Optional.ofNullable(join(fCar, left(deadline))).flatMap(e -> e);
        boolean pending = eta.isEmpty() && mobility.pending(from.lat(), from.lon(), to.lat(), to.lon(), depart, true);
        Car car = new Car(eta.map(KakaoMobilityClient.Eta::durationSec).orElse(null),
                eta.map(KakaoMobilityClient.Eta::distanceM).orElse(null), eta.map(KakaoMobilityClient.Eta::departAt).orElse(null),
                eta.map(KakaoMobilityClient.Eta::path).orElse(List.of()), pending, "KAKAO_FUTURE_DIRECTIONS");

        Observed obs = join(fObs, Duration.ofSeconds(3));        // DB 만 — 넉넉히
        JourneyDtos.Plan railOpt = join(fRail, Duration.ofSeconds(4));  // DB + 카카오 다중 길찾기 (캐시)
        var origin = join(fOrigin, left(deadline));
        var dest = join(fDest, left(deadline));
        boolean envPending = origin == null || dest == null;
        Map<String, NowDtos.PointEnv> envMap = new LinkedHashMap<>();
        envMap.put("origin", origin != null ? origin : new NowDtos.PointEnv(from.name(), null, null, null, null, null, null, null));
        envMap.put("dest", dest != null ? dest : new NowDtos.PointEnv(to.name(), null, null, null, null, null, null, null));

        // 돌발: 자동차 경로 2km 안(안내 좌표) + 수집 중인 길에 매칭된 좌표 없는 안내 — 지금 안내 중인 것만
        List<EnvDtos.Incident> inc = env.routeIncidents(car.path(), obs == null ? null : obs.corridorId(), 8);

        // ---- 판단 R-DEC-01
        DecisionRule.Car dcar = null;
        if (car.durationSec() != null) {
            dcar = new DecisionRule.Car(car.durationSec(), "KAKAO", obs == null ? null : obs.vsBaselinePct(), 0,
                    obs == null ? "" : "고속도로 관측 " + Times.ago(obs.slotTs(), now));
        }
        DecisionRule.Train dtrain = null;
        int acc = accessMin == null ? 0 : accessMin;
        if (railOpt != null && !railOpt.journeys().isEmpty()) {
            var j = railOpt.journeys().getFirst();
            acc = j.access().minutes();
            var lastLeg = j.legs().getLast();
            dtrain = new DecisionRule.Train(journeyLabel(j), j.departAt().format(com.roadrail.common.Times.HM), j.waitMin(),
                    (int) Duration.between(j.departAt(), j.arriveAt()).toMinutes(), lastLeg.avgArrDelayMin30d(),
                    lastLeg.onTimeRate30d(), lastLeg.samples(), j.egress().minutes());
        }
        var p = new DecisionRule.Params(props.decisionSimilarMin(), props.decisionPopWarn(), acc);
        var o = envMap.get("origin");
        var d = envMap.get("dest");
        var decision = DecisionRule.decide(dcar, dtrain,
                new DecisionRule.Env(o == null ? null : o.pop(), d == null ? null : d.pop(),
                        o == null ? null : o.pm25Grade(), d == null ? null : d.pm25Grade(),
                        holidays.name(Times.kst(depart).toLocalDate()).orElse(null)), inc.size(), p);
        if (km < MIN_RAIL_KM) {
            decision = new DecisionRule.Result(decision.rule(), "CAR", "가까운 거리(" + km + "km)라 기차 비교는 하지 않습니다.",
                    decision.carTotalMin(), null, null, decision.reasons(), decision.warnings());
        } else if (dtrain == null && car.durationSec() != null) {
            List<String> r = new ArrayList<>(decision.reasons().stream().filter(x -> !x.contains("데이터가 없어 한쪽만")).toList());
            r.add(railOpt == null || railOpt.note() == null ? "두 지점 근처 역을 잇는 열차가 없습니다" : railOpt.note());
            decision = new DecisionRule.Result(decision.rule(), "CAR", "이어지는 열차가 없어 자동차만 비교합니다.",
                    decision.carTotalMin(), null, null, r, decision.warnings());
        }

        Map<String, String> fresh = new LinkedHashMap<>();
        fresh.put("kakao", car.departAt() == null ? (pending ? "경로 조회 중" : "—")
                : "출발 " + car.departAt().substring(8, 10) + ":" + car.departAt().substring(10) + " 기준 경로 예측");
        fresh.put("road", obs == null ? "수집 중인 길 아님" : Times.ago(obs.slotTs(), now) + " 슬롯 (도로공사 공개 지연)");
        fresh.put("rail", railOpt == null || railOpt.referenceDate() == null ? "—" : railOpt.referenceDate() + " 운행 기준 시간표");
        fresh.put("weather", "단기예보 최근 발표");
        fresh.put("air", "시도 측정소 최근 측정");
        String caveat = "자동차는 카카오 경로 예측(출발 시각 기준)입니다. 기차는 코레일 여객열차의 환승 경로(최소 환승 "
                + RailJourneyService.TRANSFER_MIN + "분, 승차 여유 " + RailJourneyService.BOARDING_BUFFER_MIN + "분)이며, 역까지·역에서는 "
                + (accessMin == null ? "카카오 실제 운전 경로(1km 미만만 도보 추정, 경로를 얻지 못한 역은 제외)" : "역까지 입력값 " + accessMin + "분 · 역에서는 카카오 실제 경로")
                + "입니다. 지하철·버스 환승은 포함하지 않습니다. 참고 정보이며 교통 안내 서비스가 아닙니다.";
        return new Trip(from, to, km, now, depart, accessMin, car, obs, railOpt, envMap, inc, decision, fresh, caveat,
                pending || envPending || (railOpt != null && railOpt.pending()), "MISS");
    }

    /** 두 지점이 수집 중인 길의 끝(출발·도착 도시 역)과 각각 30km 안이면 그 길 · 방향 */
    public Observed observed(Place from, Place to, OffsetDateTime depart, OffsetDateTime now) {
        var match = jdbc.sql("""
                WITH p AS (
                  SELECT o.corridor_id, o.lat AS olat, o.lon AS olon, d.lat AS dlat, d.lon AS dlon
                  FROM ref.corridor_env_point o JOIN ref.corridor_env_point d
                    ON d.corridor_id = o.corridor_id AND o.role = 'origin' AND d.role = 'dest'
                  JOIN ref.corridor c ON c.corridor_id = o.corridor_id AND c.active)
                SELECT corridor_id, dir FROM (
                  SELECT corridor_id, 'DN' AS dir, greatest(dist(olat, olon, :fla, :flo), dist(dlat, dlon, :tla, :tlo)) AS m FROM p
                  UNION ALL
                  SELECT corridor_id, 'UP', greatest(dist(dlat, dlon, :fla, :flo), dist(olat, olon, :tla, :tlo)) FROM p) x
                WHERE m <= 30 ORDER BY m LIMIT 1""".replace("dist(", "ops.km("))
                .param("fla", from.lat()).param("flo", from.lon()).param("tla", to.lat()).param("tlo", to.lon())
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
        if (match.isEmpty()) return null;
        String cid = match.get()[0], dir = match.get()[1];
        Optional<Latest> latest = road.latest(cid, dir);
        if (latest.isEmpty()) return null;
        Latest l = latest.get();
        var bl = road.baselineMap(cid, dir);
        var cmp = ForecastModels.comparable(bl, l.slotTs());
        Double pct = cmp == null || cmp.p50() == 0 ? null : Math.round((l.travelSec() - cmp.p50()) * 1000.0 / cmp.p50()) / 10.0;
        var f = ForecastModels.predictAll(bl, l.slotTs(), l.travelSec(), depart, props.forecastTauMin());
        String model = f.m1() != null ? "M1" : f.m0() != null ? "M0" : "persistence";
        Integer pred = f.m1() != null ? f.m1() : f.m0() != null ? f.m0() : Integer.valueOf(f.persistence());
        String name = jdbc.sql("SELECT name FROM ref.corridor WHERE corridor_id = :c").param("c", cid).query(String.class).single();
        return new Observed(cid, name, dir, l.travelSec(), cmp == null ? null : cmp.p50(), pct, l.slotTs(), pred, model,
                (int) Duration.between(l.slotTs(), depart).toMinutes());
    }

    /** "1203 → 대전 환승 → 101" */
    static String journeyLabel(JourneyDtos.Journey j) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < j.legs().size(); i++) {
            var l = j.legs().get(i);
            if (i > 0) b.append(" → ").append(l.fromName()).append(" 환승 → ");
            b.append(l.trnNo().replaceFirst("^0+", ""));
        }
        return b.toString();
    }

    NowDtos.PointEnv pointEnv(Place p, OffsetDateTime at) {
        var cell = KmaGrid.of(p.lat(), p.lon());
        Integer pop = null, tmp = null;
        String pty = null, sky = null;
        var stored = env.at(cell.nx(), cell.ny(), at);
        if (stored.isPresent()) {
            pop = stored.get().pop();
            tmp = stored.get().tmp();
            pty = stored.get().pty();
            sky = stored.get().sky();
        } else {
            var fc = kma.forecast(cell.nx(), cell.ny());
            var h = fc == null ? null : fc.hours().get(KmaClient.hourKey(at));
            if (h != null) {
                pop = RoadService.parseInt(h.get("POP"));
                tmp = RoadService.parseInt(h.get("TMP"));
                pty = RoadService.ptyName(h.get("PTY"));
                sky = EnvService.skyName(h.get("SKY"));
            }
        }
        var region = local.region(p.lat(), p.lon());
        String sido = region == null ? null : AirKoreaClient.sidoOf(region.region1(), region.region2());
        Integer pm25 = null, g25 = null, khai = null;
        var a = sido == null ? null : env.air(sido);
        if (a != null && a.dataTime() != null && a.dataTime().isAfter(Times.now().minusHours(3))) {
            pm25 = a.pm25();
            g25 = a.pm25Grade();
            khai = a.khaiGrade();
        } else if (sido != null) {
            var live = air.sido(sido);
            if (live != null) {
                pm25 = live.pm25();
                g25 = live.pm25Grade();
                khai = live.khaiGrade();
            }
        }
        return new NowDtos.PointEnv(p.name(), pop, pty, tmp, sky, pm25, g25, khai);
    }
}
