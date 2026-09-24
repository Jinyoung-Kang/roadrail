package com.roadrail.service;

import com.roadrail.common.Times;
import com.roadrail.domain.KmaGrid;
import com.roadrail.domain.RailRouter;
import com.roadrail.external.TagoSubwayClient;
import com.roadrail.web.dto.JourneyDtos.*;
import com.roadrail.web.dto.RailDtos;
import com.roadrail.web.dto.TripDtos.Place;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 기차 여정 — 어디서 가까운 역 → (환승) → 어디로 가까운 역. 전국 코레일 여객열차 하루 시간표(rail.day_stops)에
 * CSA(RailRouter)를 돌린다. 역까지 · 역에서 이동은 1km 미만이면 도보 추정, 그 밖은 카카오 다중 목적지 · 출발지
 * 길찾기의 실제 운전 경로. 그날 남은 열차가 없으면 다음 날 시간표까지 이어서 찾는다.
 */
@Service
public class RailJourneyService {
    static final double RADIUS_KM = 30, KAKAO_RADIUS_KM = 9.5, WALK_KM = 1.0;
    static final int CANDIDATES = 6, BOARDING_BUFFER_MIN = 5, TRANSFER_MIN = 10;
    private final JdbcClient jdbc;
    private final RailService rail;
    private final KakaoMobilityClient kakao;
    private final TagoSubwayClient tago;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    /** 기준 운행일 → 연결 목록 (하루 약 1만 개). 하루 단위로 교체 */
    private final Map<LocalDate, List<RailRouter.Connection>> dayCache = new ConcurrentHashMap<>();

    public RailJourneyService(JdbcClient jdbc, RailService rail, KakaoMobilityClient kakao, TagoSubwayClient tago) {
        this.jdbc = jdbc;
        this.rail = rail;
        this.kakao = kakao;
        this.tago = tago;
    }

    /** 도보: 직선 × 1.3 ÷ 4.5km/h · 차량 추정: 직선 × 1.3 ÷ 25km/h (카카오를 못 쓸 때) */
    static int walkMin(double km) { return (int) Math.max(1, Math.round(km * 1.3 / 4.5 * 60)); }

    static int carEstimateMin(double km) { return (int) Math.max(5, Math.round(km * 1.3 / 25 * 60)); }

    List<RailRouter.Connection> connectionsFor(LocalDate refDate) {
        if (dayCache.size() > 6) dayCache.clear();
        return dayCache.computeIfAbsent(refDate, d -> {
            List<RailRouter.Stop> stops = jdbc.sql("SELECT trn_no, run_seq, stn_cd, arr_at, dep_at FROM rail.day_stops(:d)")
                    .param("d", d).query((rs, i) -> new RailRouter.Stop(rs.getString(1), rs.getInt(2), rs.getString(3),
                            epoch(rs.getObject(4, OffsetDateTime.class)), epoch(rs.getObject(5, OffsetDateTime.class)))).list();
            return RailRouter.connections(stops);
        });
    }

    private static Long epoch(OffsetDateTime t) { return t == null ? null : t.toEpochSecond(); }

    /** 목표일 시간표 = 같은 요일 최근 운행일을 목표일로 옮긴 것. 오늘 + 내일을 이어 붙인다 (심야 출발). */
    List<RailRouter.Connection> timetable(LocalDate target, List<String> basisOut, List<String> refOut) {
        List<RailRouter.Connection> all = new ArrayList<>();
        for (int k = 0; k < 2; k++) {
            LocalDate day = target.plusDays(k);
            var ref = rail.referenceDate(day);
            if (ref.isEmpty()) continue;
            long shift = Duration.between(ref.get().getKey().atStartOfDay(), day.atStartOfDay()).toSeconds();
            if (k == 0) {
                basisOut.add(ref.get().getValue());
                refOut.add(ref.get().getKey().toString());
            }
            for (var c : connectionsFor(ref.get().getKey())) {
                all.add(new RailRouter.Connection(c.trip() + "@" + k, c.from(), c.to(), c.dep() + shift, c.arr() + shift));
            }
        }
        all.sort(Comparator.comparingLong(RailRouter.Connection::dep));
        return all;
    }

    /** 어디서 → 역 이동 시간들 (역 코드 → Transfer) */
    Map<String, Transfer> accessTimes(Place p, List<RailDtos.StationNear> stations, boolean toStation, Integer overrideMin) {
        Map<String, Transfer> out = new LinkedHashMap<>();
        Map<String, double[]> viaKakao = new LinkedHashMap<>();
        for (var s : stations) {
            if (overrideMin != null && toStation) {
                out.put(s.code(), new Transfer(s.code(), s.name(), s.lat(), s.lon(), s.distanceKm(), overrideMin, null, "INPUT"));
            } else if (s.distanceKm() < WALK_KM) {
                out.put(s.code(), new Transfer(s.code(), s.name(), s.lat(), s.lon(), s.distanceKm(), walkMin(s.distanceKm()), null, "WALK"));
            } else if (s.distanceKm() <= KAKAO_RADIUS_KM) {
                viaKakao.put(s.code(), new double[]{s.lat(), s.lon()});
            }
        }
        Map<String, KakaoMobilityClient.Leg> legs = viaKakao.isEmpty() ? Map.of()
                : toStation ? kakao.manyDestinations(p.lat(), p.lon(), viaKakao) : kakao.manyOrigins(viaKakao, p.lat(), p.lon());
        for (var s : stations) {
            if (out.containsKey(s.code())) continue;
            var l = legs.get(s.code());
            if (l != null) {
                out.put(s.code(), new Transfer(s.code(), s.name(), s.lat(), s.lon(), s.distanceKm(),
                        (int) Math.max(1, Math.round(l.durationSec() / 60.0)), l.distanceM(), "CAR"));
            } else {  // 10km 밖이거나 카카오 실패 → 직선거리 추정 (화면에 '추정' 표시)
                out.put(s.code(), new Transfer(s.code(), s.name(), s.lat(), s.lon(), s.distanceKm(), carEstimateMin(s.distanceKm()), null, "ESTIMATE"));
            }
        }
        return out;
    }

    public Plan plan(Place from, Place to, OffsetDateTime depart, Integer accessOverride) {
        List<RailDtos.StationNear> origins = new ArrayList<>(rail.near(from.lat(), from.lon(), RADIUS_KM, CANDIDATES));
        List<RailDtos.StationNear> dests = new ArrayList<>(rail.near(to.lat(), to.lon(), RADIUS_KM, CANDIDATES));
        pin(origins, from);
        pin(dests, to);
        if (origins.isEmpty() || dests.isEmpty()) {
            return empty((origins.isEmpty() ? "어디서" : "어디로") + " 반경 " + (int) RADIUS_KM + "km 안에 운행 중인 기차역이 없습니다");
        }
        var fAcc = CompletableFuture.supplyAsync(() -> accessTimes(from, origins, true, accessOverride), exec);
        var fEg = CompletableFuture.supplyAsync(() -> accessTimes(to, dests, false, null), exec);
        List<String> basis = new ArrayList<>(), ref = new ArrayList<>();
        var conns = timetable(Times.kst(depart).toLocalDate(), basis, ref);
        Map<String, Transfer> acc = fAcc.join(), eg = fEg.join();
        if (conns.isEmpty()) return empty("운행 데이터가 없습니다");

        long dep0 = depart.toEpochSecond();
        List<RailRouter.Origin> o = origins.stream().map(s -> new RailRouter.Origin(s.code(),
                dep0 + (acc.get(s.code()).minutes() + BOARDING_BUFFER_MIN) * 60L)).toList();
        List<RailRouter.Dest> d = dests.stream().map(s -> new RailRouter.Dest(s.code(), eg.get(s.code()).minutes() * 60L)).toList();
        var found = RailRouter.several(conns, o, d, TRANSFER_MIN * 60L, 3);
        if (found.isEmpty()) {
            return new Plan(List.of(), ref.isEmpty() ? null : ref.getFirst(), basis.isEmpty() ? null : basis.getFirst(),
                    origins.size(), dests.size(), BOARDING_BUFFER_MIN, TRANSFER_MIN,
                    "오늘·내일 시간표에서 이어지는 열차가 없습니다", List.of(), List.of());
        }
        Map<String, String> names = new HashMap<>();
        origins.forEach(s -> names.put(s.code(), s.name()));
        dests.forEach(s -> names.put(s.code(), s.name()));
        LocalDate refDate = LocalDate.parse(ref.getFirst());
        List<Journey> journeys = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            journeys.add(toJourney(found.get(i), acc, eg, names, depart, refDate, i == 0));
        }
        Journey first = journeys.getFirst();
        // 출발역 · 도착역에서 갈아탈 수 있는 지하철 (TAGO) — 병렬로, 늦으면 비움
        var fSubDep = CompletableFuture.supplyAsync(() -> tago.next(first.access().stationName(), first.departAt().minusMinutes(40)), exec);
        var fSubArr = CompletableFuture.supplyAsync(() -> tago.next(first.egress().stationName(), first.arriveAt().plusMinutes(3)), exec);
        return new Plan(journeys, ref.getFirst(), basis.getFirst(), origins.size(), dests.size(), BOARDING_BUFFER_MIN, TRANSFER_MIN,
                "코레일 여객열차(KTX·ITX·무궁화 등) 기준. 지하철·버스 환승 경로는 공개 데이터가 없어 다루지 않습니다.",
                TripService.join(fSubDep, Duration.ofMillis(1500)), TripService.join(fSubArr, Duration.ofMillis(1500)));
    }

    private static void pin(List<RailDtos.StationNear> list, Place p) {
        if (p.stationCode() == null) return;
        list.removeIf(s -> s.code().equals(p.stationCode()));
        list.addFirst(new RailDtos.StationNear(p.stationCode(), p.name().replaceAll("역$", ""), p.lat(), p.lon(), 0));
    }

    private Plan empty(String note) {
        return new Plan(List.of(), null, null, 0, 0, BOARDING_BUFFER_MIN, TRANSFER_MIN, note, List.of(), List.of());
    }

    private Journey toJourney(RailRouter.Journey j, Map<String, Transfer> acc, Map<String, Transfer> eg, Map<String, String> names,
                              OffsetDateTime depart, LocalDate refDate, boolean withStats) {
        List<Leg> legs = new ArrayList<>();
        List<CompletableFuture<Map<String, RailDtos.TrainStats>>> stats = new ArrayList<>();
        for (var l : j.legs()) {
            String trn = l.trip().substring(0, l.trip().indexOf('@'));
            stats.add(withStats ? CompletableFuture.supplyAsync(() -> rail.stats30d(l.from(), l.to(), refDate, List.of(trn)), exec)
                    : CompletableFuture.completedFuture(Map.of()));
        }
        Double lastDelay = null;
        for (int i = 0; i < j.legs().size(); i++) {
            var l = j.legs().get(i);
            String trn = l.trip().substring(0, l.trip().indexOf('@'));
            var st = TripService.join(stats.get(i), Duration.ofSeconds(3));
            var s = st == null ? null : st.get(trn);
            OffsetDateTime dep = OffsetDateTime.ofInstant(Instant.ofEpochSecond(l.dep()), Times.KST);
            OffsetDateTime arr = OffsetDateTime.ofInstant(Instant.ofEpochSecond(l.arr()), Times.KST);
            double[] fc = coords(l.from()), tc = coords(l.to());
            legs.add(new Leg(trn, l.from(), name(l.from(), names), l.to(), name(l.to(), names), dep, arr,
                    (int) Duration.between(dep, arr).toMinutes(), s == null ? null : s.onTimeRate(),
                    s == null ? null : s.avgArrDelayMin(), s == null ? 0 : s.samples(), s != null && s.delayEstimated(),
                    fc == null ? null : fc[0], fc == null ? null : fc[1], tc == null ? null : tc[0], tc == null ? null : tc[1]));
            if (i == j.legs().size() - 1 && s != null) lastDelay = s.avgArrDelayMin();
        }
        Transfer a = acc.get(j.originStn()), e = eg.get(j.destStn());
        OffsetDateTime first = legs.getFirst().dep(), last = legs.getLast().arr();
        int wait = (int) Math.max(Duration.between(depart.plusMinutes(a.minutes()), first).toMinutes(), 0);
        double delay = lastDelay == null ? 0 : Math.max(lastDelay, 0);
        int total = (int) Math.round(a.minutes() + wait + Duration.between(first, last).toMinutes() + delay + e.minutes());
        return new Journey(a, legs, e, first, last, wait, j.transfers(), total, lastDelay);
    }

    private final Map<String, double[]> coordCache = new ConcurrentHashMap<>();

    private double[] coords(String code) {
        double[] c = coordCache.computeIfAbsent(code, k -> jdbc.sql("SELECT lat, lon FROM ref.station WHERE stn_cd = :c AND lat IS NOT NULL")
                .param("c", k).query((rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)}).optional().orElse(new double[0]));
        return c.length == 2 ? c : null;
    }

    private String name(String code, Map<String, String> names) {
        return names.computeIfAbsent(code, c -> jdbc.sql("SELECT stn_nm FROM ref.station WHERE stn_cd = :c").param("c", c)
                .query(String.class).optional().orElse(c));
    }

    /** 두 지점 직선거리 (km) */
    static double km(Place a, Place b) { return KmaGrid.km(a.lat(), a.lon(), b.lat(), b.lon()); }
}
