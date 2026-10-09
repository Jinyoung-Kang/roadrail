package com.roadrail.rail.app;

import com.roadrail.domain.CompensationRule;

import com.roadrail.env.app.HolidayService;
import com.roadrail.rail.data.RailRepository;
import com.roadrail.shared.JsonCache;
import com.roadrail.shared.ApiException;
import com.roadrail.shared.Times;
import com.roadrail.shared.AppProperties;
import com.roadrail.rail.model.RailDtos.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 철도 조회 — 모든 계산은 **역 쌍**(출발역 A → 도착역 B) 기준이며 SQL 함수 rail.od_trips (V6, 규칙 P-i1) 를 쓴다.
 * 길(미리 정한 도시 쌍)의 철도 조회도 그 길의 역 쌍으로 바꿔 같은 경로를 탄다.
 */
@Service
public class RailService {
    private final RailRepository repo;
    private final AppProperties props;
    private final TimetableService timetable;
    private final JsonCache cache;
    private final HolidayService holidays;

    public RailService(RailRepository repo, AppProperties props, TimetableService timetable, JsonCache cache,
                       HolidayService holidays) {
        this.holidays = holidays;
        this.repo = repo;
        this.props = props;
        this.timetable = timetable;
        this.cache = cache;
    }

    public record Pair(String dep, String arr) {}

    /** 길 · 방향 → 역 쌍 */
    public Pair pairOf(String cid, String dir) {
        return repo.corridorStations(cid, dir).map(p -> new Pair(p.dep(), p.arr()))
                .orElseThrow(() -> ApiException.corridorNotFound(cid));
    }

    public String stationName(String code) {
        return repo.stationName(code).orElseThrow(() -> ApiException.invalid("역 코드 '" + code + "' 를 찾을 수 없습니다."));
    }

    /** 가장 최근 운행일 (전국) */
    public Optional<LocalDate> latestDate() {
        return repo.latestRunDate();
    }

    /**
     * 앞으로의 시간표는 공개 데이터에 없어 **같은 요일의 최근 운행일** 실제 시간표를 쓴다.
     * 공휴일에는 임시열차가 섞인다(실측: 추석 09-24(목) 931편 ↔ 09-17(목) 875편) → 평일 목표일에는 공휴일을 기준일로 고르지 않는다.
     * 목표일이 공휴일이면 같은 요일 최근 운행일을 쓰고, 공휴일 임시열차는 반영되지 않는다고 밝힌다.
     */
    public Optional<Map.Entry<LocalDate, String>> referenceDate(LocalDate target) {
        boolean targetHoliday = holidays.is(target);
        List<LocalDate> same = repo.sameWeekdayRunDates(target);
        for (LocalDate d : same) {
            if (targetHoliday) {
                return Optional.of(Map.entry(d, "같은 요일 최근 운행일 · 목표일은 " + holidays.name(target).orElse("공휴일")
                        + " — 공휴일 임시열차는 반영되지 않음"));
            }
            if (!holidays.is(d)) return Optional.of(Map.entry(d, "같은 요일 최근 운행일 (공휴일 제외)"));
        }
        return latestDate().map(d -> Map.entry(d, "가장 최근 운행일 (같은 요일 없음)"));
    }

    /**
     * ready(역에 도착하는 시각) 이후 A→B 첫 열차들. 그날 남은 열차가 없으면 **다음 날 첫차**부터 (심야 출발).
     * 기준일 시간표를 목표일로 옮겨 표시한다.
     */
    public NextTrains nextTrains(String dep, String arr, OffsetDateTime ready, int limit) {
        OffsetDateTime r = Times.kst(ready);
        NextTrains today = nextTrainsOn(dep, arr, r.toLocalDate(), r.toLocalTime(), limit);
        if (!today.trains().isEmpty() || today.referenceDate() == null) return today;
        NextTrains tomorrow = nextTrainsOn(dep, arr, r.toLocalDate().plusDays(1), LocalTime.MIN, limit);
        return tomorrow.trains().isEmpty() ? today
                : new NextTrains(tomorrow.referenceDate(), tomorrow.basis() + " · 다음 날 첫차", tomorrow.trains());
    }

    NextTrains nextTrainsOn(String dep, String arr, LocalDate targetDate, LocalTime readyTime, int limit) {
        var ref = referenceDate(targetDate);
        if (ref.isEmpty()) return new NextTrains(null, "운행 데이터 없음", List.of());
        LocalDate refDate = ref.get().getKey();
        timetable.ensure(dep, arr, List.of(refDate), Duration.ofMillis(1500));
        List<RailRepository.OdTrip> rows = repo.departuresAfter(dep, arr, refDate, readyTime, limit);
        Map<String, TrainStats> stats = stats30d(dep, arr, refDate, rows.stream().map(RailRepository.OdTrip::trnNo).toList());
        long shift = ChronoUnit.DAYS.between(refDate, targetDate);
        List<NextTrain> out = new ArrayList<>();
        for (RailRepository.OdTrip r : rows) {
            OffsetDateTime d = Times.kst(r.dep()).plusDays(shift);
            OffsetDateTime a = Times.kst(r.arr()).plusDays(shift);
            TrainStats s = stats.get(r.trnNo());
            out.add(new NextTrain(r.trnNo(), d, a, d.format(Times.HM), a.format(Times.HM),
                    (int) Duration.between(d, a).toMinutes(), s == null ? null : s.avgArrDelayMin(),
                    s == null ? null : s.onTimeRate(), s == null ? 0 : s.samples()));
        }
        return new NextTrains(refDate.toString(), ref.get().getValue(), out);
    }

    public record First(String trnNo, OffsetDateTime dep, OffsetDateTime arr) {}

    /** 열차 정보 — 운행계획(rail.run_plan)의 시발·종착역으로 'OO발 OO행'. 기간 안의 가장 최근 운행일 기준. */
    public Map<String, TrainMeta> trainMeta(Collection<String> trains, LocalDate from, LocalDate to) {
        Map<String, TrainMeta> m = new HashMap<>();
        if (trains.isEmpty()) return m;
        for (RailRepository.TrainEnds e : repo.trainEnds(trains, from, to)) {
            String o = e.originName(), d = e.terminusName();
            m.put(e.trnNo(), new TrainMeta(o, d, o + "발 " + d + "행"));
        }
        return m;
    }

    /** ready 이후 A→B 첫 열차 (통계 없이 — 역 조합 비교용). 그날 없으면 다음 날 첫차. 시각은 목표일 기준으로 옮김. */
    public Optional<First> firstTrain(String dep, String arr, OffsetDateTime ready) {
        OffsetDateTime r = Times.kst(ready);
        Optional<First> f = firstOn(dep, arr, r.toLocalDate(), r.toLocalTime());
        return f.isPresent() ? f : firstOn(dep, arr, r.toLocalDate().plusDays(1), LocalTime.MIN);
    }

    private Optional<First> firstOn(String dep, String arr, LocalDate target, LocalTime ready) {
        var ref = referenceDate(target);
        if (ref.isEmpty()) return Optional.empty();
        LocalDate refDate = ref.get().getKey();
        timetable.ensure(dep, arr, List.of(refDate), Duration.ofMillis(1500));
        long shift = ChronoUnit.DAYS.between(refDate, target);
        return repo.firstDepartureAfter(dep, arr, refDate, ready)
                .map(t -> new First(t.trnNo(), Times.kst(t.dep()).plusDays(shift), Times.kst(t.arr()).plusDays(shift)));
    }

    public Map<String, TrainStats> stats30d(String dep, String arr, LocalDate end, List<String> trains) {
        if (trains.isEmpty()) return new HashMap<>();
        return repo.trainStats(dep, arr, end.minusDays(29), end, trains, props.onTimeThresholdMin());
    }

    public Trains trains(String dep, String arr, LocalDate date) {
        String cacheKey = "rail:trains:v2:%s:%s:%s".formatted(dep, arr, date == null ? "latest" : date);
        Trains hit = cache.peek(cacheKey, Trains.class);
        if (hit != null) return hit;
        String depName = stationName(dep), arrName = stationName(arr);
        LocalDate latest = latestDate().orElse(null);
        // 최근 120일 날짜별 운행 수 · 계획 시각을 확인한 수를 한 번에 — 날짜 목록과 기본 날짜를 같이 고른다
        List<RailRepository.DayCount> perDay = latest == null ? List.of() : repo.dailyCounts(dep, arr, latest.minusDays(120), latest);
        List<String> dates = perDay.stream().map(r -> r.day().toString()).toList();
        // 날짜를 고르지 않았으면: 계획 시각을 확인할 수 있는(운행의 절반 이상) 가장 최근 날짜 — TAGO 시간표가 빠진 날은 건너뜀
        LocalDate d = date != null ? date : perDay.stream().filter(r -> r.verified() >= 0.5 * r.runs())
                .map(RailRepository.DayCount::day).findFirst().orElse(dates.isEmpty() ? null : LocalDate.parse(dates.getFirst()));
        if (d == null) return new Trains(dep, arr, depName, arrName, null, List.of(), dates, "두 역을 잇는 직통 운행 기록이 없습니다.");
        boolean ready = timetable.ensure(dep, arr, timetable.runDates(dep, arr, d.minusDays(29), d), Duration.ofMillis(2500));
        List<RailRepository.RunRow> rows = repo.runs(dep, arr, d);
        Map<String, TrainStats> stats = stats30d(dep, arr, d, rows.stream().map(RailRepository.RunRow::trnNo).toList());
        Map<String, TrainMeta> meta = trainMeta(rows.stream().map(RailRepository.RunRow::trnNo).toList(), d, d);
        int thr = props.onTimeThresholdMin();
        List<TrainRun> runs = new ArrayList<>();
        for (RailRepository.RunRow r : rows) {
            Double arrDelay = r.arrDelayMin();
            runs.add(new TrainRun(r.trnNo(), Times.kst(r.actDepAt()), Times.kst(r.actArrAt()),
                    Times.kst(r.planDepAt()), Times.kst(r.planArrAt()), r.depDelayMin(), arrDelay,
                    r.depBasis(), r.arrBasis(), r.rideMin(), arrDelay == null ? null : arrDelay <= thr,
                    stats.get(r.trnNo()), meta.get(r.trnNo()), r.grade()));
        }
        Trains t = new Trains(dep, arr, depName, arrName, d.toString(), runs, dates,
                "계획 시각: 시발·종착역은 코레일 운행계획, 중간역은 TAGO 열차 시간표의 역별 계획 시각입니다. "
                        + "TAGO 시간표가 없는 날의 중간역 운행은 계획 시각을 알 수 없어 '—'(확인 불가)로 두고 통계에서 뺍니다(추정하지 않음). "
                        + "차종은 TAGO 시간표에 적힌 그날의 배정 차종입니다. 통계는 기준일까지 최근 30일. 환승 경로는 다루지 않습니다.");
        if (ready) cache.put(cacheKey, t, Duration.ofMinutes(10));  // 시간표를 받는 중이면 캐시하지 않음
        return t;
    }

    public Punctuality punctuality(String dep, String arr, LocalDate from, LocalDate to, String groupBy, Integer thresholdMin) {
        if (to.isBefore(from)) throw ApiException.invalid("to 는 from 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(from, to) > 366) throw ApiException.invalid("조회 기간은 최대 366일입니다.");
        if (dep.equals(arr)) throw ApiException.invalid("출발역과 도착역이 같습니다.");
        int thr = thresholdMin == null ? props.onTimeThresholdMin() : thresholdMin;
        if (thr < 0 || thr > 60) throw ApiException.invalid("thresholdMin 은 0~60 입니다.");
        RailRepository.GroupKey key = switch (groupBy) {
            case "train" -> RailRepository.GroupKey.TRAIN;
            case "dow" -> RailRepository.GroupKey.DOW;   // 공휴일은 요일과 따로 'H'
            case "hour" -> RailRepository.GroupKey.HOUR;
            default -> throw ApiException.invalid("groupBy 는 train · dow · hour 중 하나입니다.");
        };
        // 과거 기간 결과는 새 운행 자료(하루 세 번)나 시간표가 들어오기 전까지 같다 → 10분 캐시 (시간표를 받는 중이면 캐시하지 않음)
        String cacheKey = "rail:punct:v3:%s:%s:%s:%s:%s:%d".formatted(dep, arr, from, to, groupBy, thr);
        Punctuality hit = cache.peek(cacheKey, Punctuality.class);
        if (hit != null) return hit;

        String depName = stationName(dep), arrName = stationName(arr);
        // 처음 보는 역 쌍은 2.5초까지만 기다리고 나머지는 뒤에서 받는다 (timetablePending → 화면이 다시 부름)
        boolean ready = timetable.ensure(dep, arr, timetable.runDates(dep, arr, from, to), Duration.ofMillis(2500));
        // 한 번의 계산으로 묶음별 행 + 전체 요약 행(total) — 요약 행은 막대그래프 · 요약으로, 나머지는 묶음 항목으로
        List<PunctualityItem> items = new ArrayList<>();
        List<Bucket> hist = new ArrayList<>();
        Summary summary = new Summary(0, 0, 0, null, null, null, DelayBands.EMPTY);
        for (RailRepository.PunctualityRow r : repo.punctuality(dep, arr, from, to, thr, key)) {
            if (r.total()) {
                String[] labels = {"≤0분", "1–5분", "6–10분", "11–20분", "21–30분", ">30분"};
                for (int i = 0; i < 6; i++) hist.add(new Bucket(labels[i], r.buckets().get(i)));
                summary = new Summary(r.samples(), r.verified(), r.samples() - r.verified(), r.onTimeRate(), r.avgArrDelayMin(),
                        r.p90ArrDelayMin(), r.delayBands());
            } else {
                items.add(new PunctualityItem(r.key(), r.samples(), r.verified(), r.onTimeRate(), r.avgArrDelayMin(),
                        r.p90ArrDelayMin(), r.avgRideMin(), null, "train".equals(groupBy) ? r.grade() : null, r.delayBands()));
            }
        }
        if (hist.isEmpty()) for (String l : List.of("≤0분", "1–5분", "6–10분", "11–20분", "21–30분", ">30분")) hist.add(new Bucket(l, 0));
        items.sort("train".equals(groupBy)
                ? Comparator.comparingInt(PunctualityItem::samples).reversed().thenComparing(PunctualityItem::key)
                : Comparator.comparing(PunctualityItem::key));
        List<PunctualityItem> out = items;
        if ("train".equals(groupBy) && !items.isEmpty()) {
            Map<String, TrainMeta> meta = trainMeta(items.stream().map(PunctualityItem::key).toList(), from, to);
            out = items.stream().map(it -> new PunctualityItem(it.key(), it.samples(), it.verified(), it.onTimeRate(),
                    it.avgArrDelayMin(), it.p90ArrDelayMin(), it.avgRideMin(), meta.get(it.key()), it.grade(), it.delayBands())).toList();
        }
        Summary nation = nationwide(from, to, thr);
        Punctuality p = new Punctuality(dep, arr, depName, arrName, from.toString(), to.toString(), groupBy, thr, summary, out,
                hist, nation,
                Map.of("P-v1", "시발 출발·종착 도착을 코레일 운행계획과 정확 비교",
                        "P-t1", "중간역은 TAGO 열차 시간표의 역별 계획 시각과 정확 비교 (시간표가 없으면 확인 불가로 제외)",
                        CompensationRule.VERSION, CompensationRule.DESCRIPTION),
                "계획 시각(운행계획 · TAGO 시간표)과 운행정보(역별 실제 출발·도착)를 비교한 값. 계획 시각을 알 수 없는 열차는 "
                        + "'운행 확인 불가'로 정시율 분모에서 제외합니다. 직통 열차만 다룹니다(환승 제외)."
                        + ("dow".equals(groupBy) ? " 요일별의 H 는 공휴일(한국천문연구원 특일 정보)입니다." : ""), !ready);
        if (ready) cache.put(cacheKey, p, Duration.ofMinutes(10));
        return p;
    }

    /** 역 검색 — 이름 포함 검색, 최근 7일 운행 편수가 많은 역부터 */
    public List<Station> stations(String q, int limit) {
        return stations(q, limit, false);
    }

    /**
     * byName = true 면 가나다순 (역 선택 목록), 아니면 정확·앞부분 일치 → 운행 편수 순 (검색 추천).
     * 이름 순서는 Java 문자열 비교(UTF-16) — 한글 · 영문 · 기호가 모두 BMP 라 UTF-8 바이트순(가나다순, 예전 COLLATE "C")과 같다.
     */
    public List<Station> stations(String q, int limit, boolean byName) {
        String term = q == null ? "" : q.trim();
        Comparator<Station> order = byName ? Comparator.comparing(Station::name)
                : Comparator.comparing((Station s) -> !s.name().equals(term))
                        .thenComparing(s -> !s.name().startsWith(term))
                        .thenComparing(Comparator.comparingInt(Station::trains7d).reversed())
                        .thenComparing(Station::name);
        return activeStations().stream().filter(s -> s.name().contains(term)).sorted(order).limit(limit).toList();
    }

    public record ActiveStations(List<Station> items) {}

    /**
     * 운행 중인 역(최근 7일 정차가 있는 역)과 편수 — 역 검색 · 장소 검색은 입력할 때마다 불리는데 7일치 운행정보(약 7만 행)를
     * 매번 집계했다(48ms). 값은 운행 자료를 받을 때(하루 세 번)만 바뀌므로 최근 운행일별로 10분 캐시한다 (PERF-02).
     */
    private List<Station> activeStations() {
        LocalDate latest = latestDate().orElse(LocalDate.now(Times.KST));
        return cache.get("rail:stations:v1:" + latest, Duration.ofMinutes(10), ActiveStations.class,
                () -> new ActiveStations(repo.activeStations(latest))).value().items();
    }

    /** 같은 기간 전국 여객열차 종착역 기준 정시성(P-v1) — 역 쌍과 무관하므로 (기간, 기준)으로 따로 10분 캐시 (PERF-04) */
    Summary nationwide(LocalDate from, LocalDate to, int thr) {
        return cache.get("rail:nation:v2:%s:%s:%d".formatted(from, to, thr), Duration.ofMinutes(10), Summary.class,
                () -> repo.nationwide(from, to, thr)).value();
    }

    /** 좌표에서 가까운 (최근 14일 운행이 있는) 역 */
    public List<StationNear> near(double lat, double lon, double radiusKm, int limit) {
        LocalDate latest = latestDate().orElse(LocalDate.now(Times.KST));
        return repo.stationsNear(lat, lon, radiusKm, limit, latest);
    }
}
