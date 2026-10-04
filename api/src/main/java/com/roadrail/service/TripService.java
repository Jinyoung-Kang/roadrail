package com.roadrail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.common.JsonCache;
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
 *   <li>기차: 출발·도착 반경 30km 안의 운행 역 최대 6곳씩을 후보로 코레일 환승 경로(CSA, RailJourneyService) —
 *       '역까지 + 대기 + 탑승(환승 포함) + 최근 30일 평균 지연 + 역에서' 가 가장 이른 여정.</li>
 *   <li>날씨·대기: 수집된 값이 있으면 DB, 없으면 조회 시점 호출 (공유 예산 · 캐시).</li>
 * </ul>
 */
@Service
public class TripService {
    private static final Logger log = LoggerFactory.getLogger(TripService.class);
    static final double MIN_RAIL_KM = 15;
    static final Duration DEADLINE = Duration.ofMillis(750);
    /** 응답에 싣는 경로 돌발 목록의 상한 (최근 순) — 건수는 상한과 무관하게 전체를 경고 · incidentTotal 에 */
    static final int INCIDENT_LIST = 20;
    private final ExecutorService exec;
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
                       RailJourneyService journeys, HolidayService holidays, ExecutorService exec) {
        this.exec = exec;
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

    /** 보조 조회의 결과 — 값이 없을 때 '정말 없음'인지(완료) '아직 · 실패'인지(incomplete) 구분한다 */
    record Part<T>(T value, boolean incomplete) {}

    /**
     * 보조 조회(철도 · 길 매칭 · 날씨)를 마감까지 기다린다. 늦거나 실패하면 비워 두되 incomplete — 카드는 pending 으로 나가
     * 캐시하지 않고 화면이 다시 묻는다. 예전에는 늦으면 '없음'과 같아져 "열차 없음" 결론을 캐시했고,
     * 일시적 DB 오류 하나가 /trip 전체를 500 으로 만들었다(RVW-01).
     */
    static <T> Part<T> part(CompletableFuture<T> f, Duration wait, String what) {
        try {
            return new Part<>(f.get(wait.toMillis(), TimeUnit.MILLISECONDS), false);
        } catch (TimeoutException e) {
            return new Part<>(null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Part<>(null, true);
        } catch (ExecutionException e) {
            log.warn("{} 조회 실패 — 비워 두고 다시 묻게 함: {}", what, e.getCause().getClass().getSimpleName());
            return new Part<>(null, true);
        }
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
        // 역 지정(여정의 출발 · 도착역 고정)과 이름(응답에 그대로 나감)도 결과를 바꾸므로 키에 넣는다
        String key = String.format(Locale.ROOT, "trip:v2:%.4f,%.4f:%s:%s:%.4f,%.4f:%s:%s:%d:%s", from.lat(), from.lon(),
                Objects.requireNonNullElse(from.stationCode(), "-"), from.name(), to.lat(), to.lon(),
                Objects.requireNonNullElse(to.stationCode(), "-"), to.name(), departIn, accessMin == null ? "auto" : accessMin);
        Trip hit = cache.peek(key, Trip.class);
        if (hit != null) return hit.withCache("HIT");
        Trip t = build(from, to, departIn, accessMin);
        if (!t.pending()) cache.put(key, t, Duration.ofSeconds(props.nowCacheSeconds()));  // 외부 조회 대기 중이면 캐시하지 않음
        return t;
    }

    /** 대한민국 범위 안의 유한한 좌표만. '범위 안' 조건으로 쓴다 — NaN 은 모든 비교가 거짓이라 '범위 밖(<, >)' 조건을 통과한다 */
    public static void validate(Place p) {
        boolean inside = p.lat() >= 33 && p.lat() <= 39 && p.lon() >= 124 && p.lon() <= 132;
        if (!inside) throw ApiException.invalid("대한민국 안의 좌표만 지원합니다: " + p.name());
    }

    Trip build(Place from, Place to, int departIn, Integer accessMin) {
        OffsetDateTime now = Times.now();
        OffsetDateTime depart = Times.alignTo5Min(now).plusMinutes(departIn);
        double km = Math.round(KmaGrid.km(from.lat(), from.lon(), to.lat(), to.lon()) * 10) / 10.0;

        // 자동차 · 기차 · 양쪽 날씨·대기 · 수집 중인 길 매칭을 가상 스레드로 동시에 (응답 예산 NFR-03).
        // 외부 조회가 늦으면 WAIT 까지만 기다리고 비워 둔다 — 조회는 계속되어 캐시를 채우고, 화면은 pending 을 보고 다시 부른다.
        long started = System.nanoTime();
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
                eta.map(KakaoMobilityClient.Eta::path).orElse(List.of()), eta.map(KakaoMobilityClient.Eta::traffic).orElse(List.of()),
                pending, "KAKAO_FUTURE_DIRECTIONS");

        // 시작 시각 기준 마감 — 앞의 대기가 길어도 뒤의 대기가 그만큼 더해지지 않는다(예전: 3초 + 4초)
        var obsPart = part(fObs, left(started + Duration.ofSeconds(3).toNanos()), "길 매칭");              // DB 만 — 넉넉히
        var railPart = part(fRail, left(started + Duration.ofSeconds(4).toNanos()), "기차 여정");         // DB + 카카오 다중 길찾기 (캐시)
        var originPart = part(fOrigin, left(deadline), "출발지 날씨");
        var destPart = part(fDest, left(deadline), "도착지 날씨");
        Observed obs = obsPart.value();
        JourneyDtos.Plan railOpt = railPart.value();
        var origin = originPart.value();
        var dest = destPart.value();
        boolean envPending = origin == null || dest == null;
        boolean incomplete = obsPart.incomplete() || railPart.incomplete();
        Map<String, NowDtos.PointEnv> envMap = new LinkedHashMap<>();
        envMap.put("origin", origin != null ? origin : new NowDtos.PointEnv(from.name(), null, null, null, null, null, null, null, null, null));
        envMap.put("dest", dest != null ? dest : new NowDtos.PointEnv(to.name(), null, null, null, null, null, null, null, null, null));

        // 돌발: 자동차 경로 위(안내 좌표 — 도로공사 2km · UTIC 0.5km) + 수집 중인 길에 매칭된 좌표 없는 안내 — 지금 안내 중인 것만.
        // 경고의 건수는 전체, 응답 목록은 최근 INCIDENT_LIST 건 (UTIC 를 더하자 긴 경로가 예전 상한 8건에 걸려 건수까지 줄어 보였다)
        List<EnvDtos.Incident> near = env.routeIncidents(car.path(), obs == null ? null : obs.corridorId());
        List<EnvDtos.Incident> inc = near.size() > INCIDENT_LIST ? List.copyOf(near.subList(0, INCIDENT_LIST)) : near;

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
                        holidays.name(Times.kst(depart).toLocalDate()).orElse(null), o == null ? null : o.pty(), d == null ? null : d.pty()),
                near.size(), p);
        if (km < MIN_RAIL_KM) {
            decision = new DecisionRule.Result(decision.rule(), "CAR", "가까운 거리(" + km + "km)라 기차 비교는 하지 않습니다.",
                    decision.carTotalMin(), null, null, decision.reasons(), decision.warnings());
        } else if (dtrain == null && car.durationSec() != null && !railPart.incomplete()) {  // 계산 중이면 '열차 없음'이 아니다
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
        fresh.put("weather", o != null && o.weatherSource() != null && !"단기예보".equals(o.weatherSource()) ? o.weatherSource() : "단기예보 최근 발표");
        fresh.put("air", "시도 측정소 최근 측정");
        String caveat = "자동차는 카카오 경로 예측(출발 시각 기준)입니다. 기차는 코레일 여객열차의 환승 경로(최소 환승 "
                + RailJourneyService.TRANSFER_MIN + "분, 승차 여유 " + RailJourneyService.BOARDING_BUFFER_MIN + "분)이며, 역까지·역에서는 "
                + (accessMin == null ? "카카오 실제 운전 경로(1km 미만만 도보 추정, 경로를 얻지 못한 역은 제외)" : "역까지 입력값 " + accessMin + "분 · 역에서는 카카오 실제 경로")
                + "입니다. 지하철·버스 환승은 포함하지 않습니다. 참고 정보이며 교통 안내 서비스가 아닙니다.";
        return new Trip(from, to, km, now, depart, accessMin, car, obs, railOpt, envMap, inc, near.size(), decision, fresh, caveat,
                pending || envPending || incomplete || (railOpt != null && railOpt.pending()), "MISS");
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
                  SELECT corridor_id, 'DN' AS dir, greatest(ops.km(olat, olon, :fla, :flo), ops.km(dlat, dlon, :tla, :tlo)) AS m FROM p
                  UNION ALL
                  SELECT corridor_id, 'UP', greatest(ops.km(dlat, dlon, :fla, :flo), ops.km(olat, olon, :tla, :tlo)) FROM p) x
                WHERE m <= 30 ORDER BY m LIMIT 1""")
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

    /** 초단기예보가 덮는 출발 시각(발표 뒤 6시간) · 초단기실황을 쓰는 '지금 출발'의 범위 */
    static final Duration ULTRA_HORIZON = Duration.ofHours(5);
    static final Duration NOWCAST_WINDOW = Duration.ofMinutes(20);

    /** 날씨 값과 그 근거 — 단기예보 위에 초단기예보 · 실황을 덮어 쓴다 */
    record Weather(Integer pop, String pty, Integer tmp, String sky, String rain, String source) {}

    NowDtos.PointEnv pointEnv(Place p, OffsetDateTime at) {
        var cell = KmaGrid.of(p.lat(), p.lon());
        Duration ahead = Duration.between(Times.now(), at);
        // 초단기(매시 발표)는 3시간마다인 단기예보보다 최신 — 가까운 출발이면 함께 받는다. 세 조회를 동시에(캐시 적중이면 즉시)
        var fUltra = ahead.compareTo(ULTRA_HORIZON) <= 0
                ? CompletableFuture.supplyAsync(() -> kma.ultraShort(cell.nx(), cell.ny()), exec) : null;
        var fNow = ahead.abs().compareTo(NOWCAST_WINDOW) <= 0
                ? CompletableFuture.supplyAsync(() -> kma.nowcast(cell.nx(), cell.ny()), exec) : null;
        Integer pop = null, tmp = null;
        String pty = null, sky = null;
        var stored = env.at(cell.nx(), cell.ny(), at);
        if (stored.isPresent()) {
            pop = stored.get().pop();
            tmp = stored.get().tmp();
            pty = stored.get().pty();
            sky = stored.get().sky();
        } else {
            var h = shortTermHour(kma.forecast(cell.nx(), cell.ny()),
                    () -> kma.forecast(cell.nx(), cell.ny(), KmaClient.latestBase(Times.now()).minusHours(3)), at);
            if (h != null) {
                pop = RoadService.parseInt(h.get("POP"));
                tmp = RoadService.parseInt(h.get("TMP"));
                pty = RoadService.ptyName(h.get("PTY"));
                sky = EnvService.skyName(h.get("SKY"));
            }
        }
        Weather w = mergeWeather(new Weather(pop, pty, tmp, sky, null, pop == null && pty == null && tmp == null ? null : "단기예보"),
                fUltra == null ? null : join(fUltra, Duration.ofSeconds(5)), fNow == null ? null : join(fNow, Duration.ofSeconds(5)), at);
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
        return new NowDtos.PointEnv(p.name(), w.pop(), w.pty(), w.tmp(), w.sky(), pm25, g25, khai, w.rain(), w.source());
    }

    /**
     * 단기예보(강수확률 포함) 위에 초단기예보(출발 시각이 발표 6시간 안이면 기온 · 하늘 · 강수형태 · 1시간 강수량)를,
     * 그 위에 초단기실황(지금 출발이면 관측 강수형태 · 기온 · 1시간 강수량)을 덮는다. 강수확률은 단기예보에만 있어 그대로.
     */
    static Weather mergeWeather(Weather base, KmaClient.Forecast ultra, KmaClient.Observation now, OffsetDateTime at) {
        Weather w = base;
        var h = ultra == null || ultra.hours() == null ? null : ultra.hours().get(KmaClient.hourKey(at));
        if (h != null && h.get("PTY") != null) {
            w = new Weather(w.pop(), RoadService.ptyName(h.get("PTY")), orElse(RoadService.parseInt(h.get("T1H")), w.tmp()),
                    orElse(EnvService.skyName(h.get("SKY")), w.sky()), forecastRain(h.get("RN1")), "초단기예보 " + hm(ultra.baseAt()) + " 발표");
        }
        var v = now == null ? null : now.values();
        if (v != null && v.get("PTY") != null) {
            Integer t = v.get("T1H") == null ? null : parseRounded(v.get("T1H"));
            w = new Weather(w.pop(), RoadService.ptyName(v.get("PTY")), orElse(t, w.tmp()), w.sky(), observedRain(v.get("RN1")),
                    "초단기실황 " + hm(now.baseAt()) + " 관측");
        }
        return w;
    }

    /**
     * 단기예보의 출발 시각 값. 새 발표는 발표 +1시간부터라 발표 직후 한 시간은 지금 시간대가 없다
     * (17:15~18:00 에 '지금 출발'이면 17시 발표에 17시가 없어 날씨가 모두 비었다 — 3시간마다 약 45분). 그때만 직전 발표에서 읽는다.
     */
    static Map<String, String> shortTermHour(KmaClient.Forecast latest, java.util.function.Supplier<KmaClient.Forecast> previous,
                                             OffsetDateTime at) {
        String key = KmaClient.hourKey(at);
        var h = latest == null || latest.hours() == null ? null : latest.hours().get(key);
        if (h != null) return h;
        var prev = previous.get();
        return prev == null || prev.hours() == null ? null : prev.hours().get(key);
    }

    /** 초단기예보 RN1: "강수없음" · "1mm 미만" · "2.0mm" · "30.0~50.0mm" · "50.0mm 이상" → 강수없음만 null */
    static String forecastRain(String rn1) {
        return rn1 == null || rn1.isBlank() || rn1.contains("없음") ? null : rn1.trim();
    }

    /** 초단기실황 RN1: mm 숫자. 0 · 결측(음수 · 비정상 큰 값)은 null */
    static String observedRain(String rn1) {
        try {
            double mm = Double.parseDouble(rn1.trim());
            return mm > 0 && mm < 300 ? (mm < 1 ? "1mm 미만" : String.format(Locale.ROOT, "%.1fmm", mm)) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer parseRounded(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return d < -80 || d > 60 ? null : (int) Math.round(d);   // 결측값(-998.9 등) 거름
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T orElse(T v, T fallback) {
        return v != null ? v : fallback;
    }

    /** baseAt(ISO 오프셋 시각) → HH:mm */
    private static String hm(String baseAt) {
        try {
            return OffsetDateTime.parse(baseAt).format(com.roadrail.common.Times.HM);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
