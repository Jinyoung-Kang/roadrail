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
    private final TimetableService timetable;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    /** 기준 운행일 → 연결 목록 (하루 약 1만 개) + 열차별 정차역 순서. 하루 단위로 교체 */
    record Day(List<RailRouter.Connection> connections, Map<String, List<String>> stopsByTrip) {}

    private final Map<LocalDate, Day> dayCache = new ConcurrentHashMap<>();
    /** (출발역, 도착역) → OSM 선로 경로 [[lat, lon], …] (ref.rail_link, 10분마다 · 비어 있으면 30초마다 다시 읽음) */
    private volatile Map<String, List<double[]>> links = Map.of();
    private volatile long linksLoadedAt = 0;

    public RailJourneyService(JdbcClient jdbc, RailService rail, KakaoMobilityClient kakao, TagoSubwayClient tago,
                              TimetableService timetable) {
        this.timetable = timetable;
        this.jdbc = jdbc;
        this.rail = rail;
        this.kakao = kakao;
        this.tago = tago;
    }

    /**
     * 도보(1km 미만): 직선 × 1.3 ÷ 4.5km/h — 공개 도보 길찾기 API 가 없어 남겨 둔 유일한 이동 시간 추정(화면에 '도보 추정').
     * 1km 이상은 모두 카카오 실제 운전 경로이며, 경로를 얻지 못한 역은 추정하지 않고 후보에서 뺀다.
     */
    static int walkMin(double km) { return (int) Math.max(1, Math.round(km * 1.3 / 4.5 * 60)); }

    /** 역별 이동 시간 + 카카오 조회가 아직 진행 중인지 (진행 중이면 화면이 잠시 뒤 다시 부른다) */
    record Access(Map<String, Transfer> byStation, boolean pending) {}

    Day day(LocalDate refDate) {
        if (dayCache.size() > 6) dayCache.clear();
        return dayCache.computeIfAbsent(refDate, d -> {
            List<RailRouter.Stop> stops = jdbc.sql("SELECT trn_no, run_seq, stn_cd, arr_at, dep_at FROM rail.day_stops(:d)")
                    .param("d", d).query((rs, i) -> new RailRouter.Stop(rs.getString(1), rs.getInt(2), rs.getString(3),
                            epoch(rs.getObject(4, OffsetDateTime.class)), epoch(rs.getObject(5, OffsetDateTime.class)))).list();
            Map<String, List<RailRouter.Stop>> byTrip = new HashMap<>();
            for (var st : stops) byTrip.computeIfAbsent(st.trip(), k -> new ArrayList<>()).add(st);
            Map<String, List<String>> seq = new HashMap<>();
            byTrip.forEach((k, v) -> seq.put(k, v.stream().sorted(Comparator.comparingInt(RailRouter.Stop::seq)).map(RailRouter.Stop::stn).toList()));
            return new Day(RailRouter.connections(stops), seq);
        });
    }

    List<RailRouter.Connection> connectionsFor(LocalDate refDate) { return day(refDate).connections(); }

    Map<String, List<double[]>> links() {
        if (System.currentTimeMillis() - linksLoadedAt > (links.isEmpty() ? 30_000 : 600_000)) {
            Map<String, List<double[]>> m = new HashMap<>();
            jdbc.sql("SELECT dep_stn_cd, arr_stn_cd, path::text FROM ref.rail_link").query(rs -> {
                m.put(rs.getString(1) + ">" + rs.getString(2), parsePath(rs.getString(3)));
            });
            links = m;
            linksLoadedAt = System.currentTimeMillis();
        }
        return links;
    }

    static List<double[]> parsePath(String json) {
        List<double[]> out = new ArrayList<>();
        var mt = java.util.regex.Pattern.compile("\\[\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*\\]").matcher(json);
        while (mt.find()) out.add(new double[]{Double.parseDouble(mt.group(1)), Double.parseDouble(mt.group(2))});
        return out;
    }

    /**
     * 열차가 실제로 정차하는 역 순서대로, 역 쌍마다 OSM 선로 경로를 이어 붙인다.
     * 선로 경로가 없는 역 쌍(OSM 에 선로가 없거나 역을 못 붙인 경우)은 두 역을 직선으로 잇고 onTrack=false.
     */
    record TrackPath(List<double[]> path, boolean onTrack) {}

    TrackPath legPath(LocalDate refDate, String trn, String from, String to) {
        List<String> seq = day(refDate).stopsByTrip().getOrDefault(trn, List.of());
        int i = seq.indexOf(from);
        int j = i < 0 ? -1 : seq.subList(i + 1, seq.size()).indexOf(to);
        List<String> stops = i < 0 || j < 0 ? List.of(from, to) : seq.subList(i, i + 1 + j + 1);
        Map<String, List<double[]>> lk = links();
        List<double[]> out = new ArrayList<>();
        boolean onTrack = true;
        for (int k = 0; k + 1 < stops.size(); k++) {
            String a = stops.get(k), b = stops.get(k + 1);
            List<double[]> p = lk.get(a + ">" + b);
            if (p == null && lk.containsKey(b + ">" + a)) {
                p = new ArrayList<>(lk.get(b + ">" + a));
                Collections.reverse(p);
            }
            if (p == null) {
                onTrack = false;
                double[] ca = coords(a), cb = coords(b);
                p = ca == null || cb == null ? List.of() : List.of(ca, cb);
            }
            if (!out.isEmpty() && !p.isEmpty()) p = p.subList(1, p.size());
            out.addAll(p);
        }
        return new TrackPath(out, onTrack);
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
                all.add(new RailRouter.Connection(c.trip() + "@" + ref.get().getKey(), c.from(), c.to(), c.dep() + shift, c.arr() + shift));
            }
        }
        all.sort(Comparator.comparingLong(RailRouter.Connection::dep));
        return all;
    }

    /** 어디서 → 역 이동 시간들 (역 코드 → Transfer) */
    Access accessTimes(Place p, List<RailDtos.StationNear> stations, boolean toStation, Integer overrideMin, OffsetDateTime depart) {
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
        // 다중 길찾기 반경(10km) 밖이거나 다중 조회에 빠진 역 → 한 곳씩 카카오 경로 (병렬, 20분 캐시)
        Map<String, CompletableFuture<Optional<KakaoMobilityClient.Eta>>> single = new LinkedHashMap<>();
        for (var s : stations) {
            if (out.containsKey(s.code()) || legs.containsKey(s.code())) continue;
            single.put(s.code(), CompletableFuture.supplyAsync(() -> toStation
                    ? kakao.futureEta(p.lat(), p.lon(), s.lat(), s.lon(), depart)
                    : kakao.futureEta(s.lat(), s.lon(), p.lat(), p.lon(), depart), exec));
        }
        boolean pending = false;
        for (var s : stations) {
            if (out.containsKey(s.code())) continue;
            var l = legs.get(s.code());
            if (l != null) {
                out.put(s.code(), new Transfer(s.code(), s.name(), s.lat(), s.lon(), s.distanceKm(),
                        (int) Math.max(1, Math.round(l.durationSec() / 60.0)), l.distanceM(), "CAR"));
                continue;
            }
            var e = TripService.join(single.get(s.code()), Duration.ofSeconds(2));
            if (e != null && e.isPresent()) {
                out.put(s.code(), new Transfer(s.code(), s.name(), s.lat(), s.lon(), s.distanceKm(),
                        (int) Math.max(1, Math.round(e.get().durationSec() / 60.0)), e.get().distanceM(), "CAR"));
            } else {
                // 경로를 모르면 추정하지 않고 이 역을 뺀다. 조회가 진행 중이면 pending → 화면이 다시 불러 캐시로 채움
                pending |= toStation ? kakao.pending(p.lat(), p.lon(), s.lat(), s.lon(), depart, false)
                        : kakao.pending(s.lat(), s.lon(), p.lat(), p.lon(), depart, false);
            }
        }
        return new Access(out, pending);
    }

    public Plan plan(Place from, Place to, OffsetDateTime depart, Integer accessOverride) {
        List<RailDtos.StationNear> origins = new ArrayList<>(rail.near(from.lat(), from.lon(), RADIUS_KM, CANDIDATES));
        List<RailDtos.StationNear> dests = new ArrayList<>(rail.near(to.lat(), to.lon(), RADIUS_KM, CANDIDATES));
        pin(origins, from);
        pin(dests, to);
        if (origins.isEmpty() || dests.isEmpty()) {
            return empty((origins.isEmpty() ? "출발지" : "도착지") + " 반경 " + (int) RADIUS_KM + "km 안에 운행 중인 기차역이 없습니다");
        }
        var fAcc = CompletableFuture.supplyAsync(() -> accessTimes(from, origins, true, accessOverride, depart), exec);
        var fEg = CompletableFuture.supplyAsync(() -> accessTimes(to, dests, false, null, depart), exec);
        List<String> basis = new ArrayList<>(), ref = new ArrayList<>();
        var conns = timetable(Times.kst(depart).toLocalDate(), basis, ref);
        Access accR = fAcc.join(), egR = fEg.join();
        Map<String, Transfer> acc = accR.byStation(), eg = egR.byStation();
        boolean pending = accR.pending() || egR.pending();
        if (conns.isEmpty()) return empty("운행 데이터가 없습니다");
        origins.removeIf(s -> !acc.containsKey(s.code()));   // 이동 시간을 모르는 역은 후보에서 제외
        dests.removeIf(s -> !eg.containsKey(s.code()));
        if (origins.isEmpty() || dests.isEmpty()) {
            Plan e = empty((origins.isEmpty() ? "출발지에서 역까지" : "역에서 도착지까지") + " 경로를 아직 계산하지 못했습니다");
            return new Plan(e.journeys(), null, null, 0, 0, BOARDING_BUFFER_MIN, TRANSFER_MIN, e.note(), List.of(), List.of(), pending);
        }

        long dep0 = depart.toEpochSecond();
        List<RailRouter.Origin> o = origins.stream().map(s -> new RailRouter.Origin(s.code(),
                dep0 + (acc.get(s.code()).minutes() + BOARDING_BUFFER_MIN) * 60L)).toList();
        List<RailRouter.Dest> d = dests.stream().map(s -> new RailRouter.Dest(s.code(), eg.get(s.code()).minutes() * 60L)).toList();
        var found = RailRouter.several(conns, o, d, TRANSFER_MIN * 60L, 3);
        if (found.isEmpty()) {
            return new Plan(List.of(), ref.isEmpty() ? null : ref.getFirst(), basis.isEmpty() ? null : basis.getFirst(),
                    origins.size(), dests.size(), BOARDING_BUFFER_MIN, TRANSFER_MIN,
                    "오늘·내일 시간표에서 이어지는 열차가 없습니다", List.of(), List.of(), pending);
        }
        Map<String, String> names = new HashMap<>();
        origins.forEach(s -> names.put(s.code(), s.name()));
        dests.forEach(s -> names.put(s.code(), s.name()));
        LocalDate refDate = LocalDate.parse(ref.getFirst());
        List<Journey> journeys = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Journey jn = toJourney(found.get(i), acc, eg, names, depart, refDate, journeys.isEmpty());
            if (jn != null) journeys.add(jn);  // 실제 시간표로 확인하니 환승·승차가 안 되는 여정은 뺀다
        }
        if (journeys.isEmpty()) {
            return new Plan(List.of(), ref.getFirst(), basis.getFirst(), origins.size(), dests.size(), BOARDING_BUFFER_MIN, TRANSFER_MIN,
                    "실제 시간표로 확인하니 이어지는 열차가 없습니다", List.of(), List.of(), pending);
        }
        Journey first = journeys.getFirst();
        // 출발역 · 도착역에서 갈아탈 수 있는 지하철 (TAGO) — 병렬로, 늦으면 비움
        var fSubDep = CompletableFuture.supplyAsync(() -> tago.next(first.access().stationName(), first.departAt().minusMinutes(40)), exec);
        var fSubArr = CompletableFuture.supplyAsync(() -> tago.next(first.egress().stationName(), first.arriveAt().plusMinutes(3)), exec);
        return new Plan(journeys, ref.getFirst(), basis.getFirst(), origins.size(), dests.size(), BOARDING_BUFFER_MIN, TRANSFER_MIN,
                "코레일 여객열차(KTX·ITX·무궁화 등) 기준. 지하철·버스 환승 경로는 공개 데이터가 없어 다루지 않습니다.",
                TripService.join(fSubDep, Duration.ofMillis(1500)), TripService.join(fSubArr, Duration.ofMillis(1500)), pending);
    }

    private static void pin(List<RailDtos.StationNear> list, Place p) {
        if (p.stationCode() == null) return;
        list.removeIf(s -> s.code().equals(p.stationCode()));
        list.addFirst(new RailDtos.StationNear(p.stationCode(), p.name().replaceAll("역$", ""), p.lat(), p.lon(), 0));
    }

    private Plan empty(String note) {
        return new Plan(List.of(), null, null, 0, 0, BOARDING_BUFFER_MIN, TRANSFER_MIN, note, List.of(), List.of(), false);
    }

    /** 구간의 기준일 실제 시간표 (운행계획 EXACT · TAGO TT) */
    record Planned(OffsetDateTime dep, OffsetDateTime arr, String grade) {}

    Planned planned(String from, String to, String trn, LocalDate ref) {
        return jdbc.sql("""
                SELECT est_plan_dep_at, est_plan_arr_at, grade, dep_basis, arr_basis FROM rail.od_trips(:a, :b, :r, :r)
                WHERE trn_no = :t LIMIT 1""")
                .param("a", from).param("b", to).param("r", ref).param("t", trn)
                .query((rs, i) -> {
                    boolean real = List.of("EXACT", "TT").contains(rs.getString(4)) && List.of("EXACT", "TT").contains(rs.getString(5));
                    return new Planned(real ? rs.getObject(1, OffsetDateTime.class) : null,
                            real ? rs.getObject(2, OffsetDateTime.class) : null, rs.getString(3));
                }).optional().orElse(new Planned(null, null, null));
    }

    /**
     * CSA 는 하루 시간표(day_stops — 중간역 계획 시각은 보간)로 경로를 찾는다. 화면에 내는 구간 시각은
     * 기준일 **실제 시간표**(코레일 운행계획 · TAGO 역별 계획 시각)로 바꾸고, 그 시각으로 승차 여유 · 최소 환승을 다시 확인한다.
     * 성립하지 않으면 null (여정 제외). 시간표를 못 받은 구간은 CSA 시각을 그대로 두고 timetable=false 로 표시.
     */
    private Journey toJourney(RailRouter.Journey j, Map<String, Transfer> acc, Map<String, Transfer> eg, Map<String, String> names,
                              OffsetDateTime depart, LocalDate refDate, boolean withStats) {
        List<Leg> legs = new ArrayList<>();
        // 구간별 기준일 시간표 (역 쌍 · 날짜당 TAGO 1건, 받은 뒤에는 DB) — 병렬로
        List<CompletableFuture<Planned>> plans = new ArrayList<>();
        for (var l : j.legs()) {
            String trn = l.trip().substring(0, l.trip().indexOf('@'));
            LocalDate legRef = LocalDate.parse(l.trip().substring(l.trip().indexOf('@') + 1));
            plans.add(CompletableFuture.supplyAsync(() -> {
                timetable.ensure(l.from(), l.to(), List.of(legRef), Duration.ofMillis(1500));
                return planned(l.from(), l.to(), trn, legRef);
            }, exec));
            // 30일 통계용 시간표는 뒤에서 받아 둔다 (다음 조회부터 보간 대신 실제 비교)
            if (withStats) timetable.ensure(l.from(), l.to(), timetable.runDates(l.from(), l.to(), refDate.minusDays(29), refDate), Duration.ZERO);
        }
        Map<String, RailDtos.TrainMeta> meta = rail.trainMeta(j.legs().stream().map(l -> l.trip().substring(0, l.trip().indexOf('@'))).toList(),
                refDate.minusDays(7), refDate.plusDays(1));
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
            Planned pl = TripService.join(plans.get(i), Duration.ofSeconds(3));
            boolean real = pl != null && pl.dep() != null;
            if (real) {  // 기준일 → 목표일: CSA 가 옮긴 날짜 수만큼
                long shiftDays = Math.round((l.dep() - pl.dep().toEpochSecond()) / 86400.0);
                dep = Times.kst(pl.dep()).plusDays(shiftDays);
                arr = Times.kst(pl.arr()).plusDays(shiftDays);
            }
            double[] fc = coords(l.from()), tc = coords(l.to());
            LocalDate legRef = LocalDate.parse(l.trip().substring(l.trip().indexOf('@') + 1));
            TrackPath tp = legPath(legRef, trn, l.from(), l.to());
            legs.add(new Leg(trn, l.from(), name(l.from(), names), l.to(), name(l.to(), names), dep, arr,
                    (int) Duration.between(dep, arr).toMinutes(), s == null ? null : s.onTimeRate(),
                    s == null ? null : s.avgArrDelayMin(), s == null ? 0 : s.samples(),
                    fc == null ? null : fc[0], fc == null ? null : fc[1], tc == null ? null : tc[0], tc == null ? null : tc[1],
                    meta.get(trn), tp.path(), tp.onTrack(), pl == null ? null : pl.grade(), real));
            if (i == j.legs().size() - 1 && s != null) lastDelay = s.avgArrDelayMin();
        }
        Transfer a = acc.get(j.originStn()), e = eg.get(j.destStn());
        // 실제 시각으로 다시 확인: 역 도착 + 승차 여유 ≤ 첫 열차 출발, 앞 열차 도착 + 최소 환승 ≤ 다음 열차 출발
        if (legs.getFirst().dep().isBefore(depart.plusMinutes(a.minutes() + BOARDING_BUFFER_MIN))) return null;
        for (int i = 1; i < legs.size(); i++) {
            if (legs.get(i).dep().isBefore(legs.get(i - 1).arr().plusMinutes(TRANSFER_MIN))) return null;
        }
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
