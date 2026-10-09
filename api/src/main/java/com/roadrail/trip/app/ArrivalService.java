package com.roadrail.trip.app;

import com.roadrail.domain.ArrivalOdds;
import com.roadrail.domain.DelayDistribution;
import com.roadrail.domain.KmaGrid;
import com.roadrail.domain.LatestDeparture;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.rail.app.RailService;
import com.roadrail.shared.AppProperties;
import com.roadrail.shared.JsonCache;
import com.roadrail.shared.Times;
import com.roadrail.trip.model.ArrivalDtos.*;
import com.roadrail.trip.model.JourneyDtos;
import com.roadrail.trip.model.TripDtos.Place;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 도착 시각 기준 판단 (AR-1 · AR-2, ADR-029) — "기한까지 도착하려면 늦어도 언제 떠나야 하나".
 * <ul>
 *   <li>기차: 거꾸로 찾은 후보(RailJourneyService.latestPlan)마다 최근 30일 실제 지연 분포로 기한 안 도착 확률을 매기고,
 *       신뢰 수준 이상인 것 중 가장 늦게 떠나는 여정을 고른다.</li>
 *   <li>자동차: 카카오 미래 운행 정보(예측)로 기한 안에 도착하는 가장 늦은 출발(10분 단위, 호출 4번 이하). 확률은 내지 않는다(D1-A).</li>
 * </ul>
 */
@Service
public class ArrivalService {
    static final int CAR_MAX_CALLS = 4, ALTERNATIVES = 3;
    static final Duration TRAIN_WAIT = Duration.ofMillis(4500);
    public static final List<Double> CONFIDENCES = List.of(0.8, 0.9, 0.95);
    static final List<String> ASSUMPTIONS = List.of(
            "확률은 최근 30일 같은 열차 · 같은 역 쌍의 실제 도착 지연 빈도입니다(보장이 아닙니다).",
            "환승 여정은 구간 지연이 서로 독립이라고 보고 곱합니다 — 같은 날 지연이 겹치면 실제보다 높게 나올 수 있습니다.",
            "환승할 때 승차 여유 " + RailJourneyService.BOARDING_BUFFER_MIN + "분을 둡니다. 다음 열차의 지연은 반영하지 않습니다.",
            "운행 취소는 운행 기록에 없어 반영되지 않습니다.",
            "역까지 · 역에서 이동 시간은 카카오 경로 예측(1km 미만은 도보 추정)입니다.",
            "자동차는 카카오 예측 소요만 씁니다 — 예측 오차 근거가 준비되기 전이라 확률은 내지 않습니다.");

    private final RailJourneyService journeys;
    private final RailService rail;
    private final KakaoMobilityClient mobility;
    private final JsonCache cache;
    private final AppProperties props;
    private final ExecutorService exec;

    public ArrivalService(RailJourneyService journeys, RailService rail, KakaoMobilityClient mobility, JsonCache cache,
                          AppProperties props, ExecutorService exec) {
        this.journeys = journeys;
        this.rail = rail;
        this.mobility = mobility;
        this.cache = cache;
        this.props = props;
        this.exec = exec;
    }

    public Arrival arrival(Place from, Place to, OffsetDateTime arriveBy, double confidence, Integer accessMin) {
        TripService.validate(from);
        TripService.validate(to);
        String key = String.format(Locale.ROOT, "arrival:v2:%.4f,%.4f:%s:%s:%.4f,%.4f:%s:%s:%s:%.2f:%s", from.lat(), from.lon(),
                Objects.requireNonNullElse(from.stationCode(), "-"), from.name(), to.lat(), to.lon(),
                Objects.requireNonNullElse(to.stationCode(), "-"), to.name(), arriveBy.toEpochSecond(), confidence,
                accessMin == null ? "auto" : accessMin);
        Arrival hit = cache.peek(key, Arrival.class);
        if (hit != null) return hit.withCache("HIT");
        Arrival a = build(from, to, arriveBy, confidence, accessMin);
        if (!a.pending()) cache.put(key, a, Duration.ofSeconds(props.nowCacheSeconds()));
        return a;
    }

    Arrival build(Place from, Place to, OffsetDateTime arriveBy, double confidence, Integer accessMin) {
        OffsetDateTime now = Times.now();
        double km = KmaGrid.km(from.lat(), from.lon(), to.lat(), to.lon());
        boolean railFar = km >= TripService.MIN_RAIL_KM;
        var fTrain = CompletableFuture.supplyAsync(() -> railFar ? train(from, to, arriveBy, confidence, accessMin, now) : null, exec);
        CarResult car = car(from, to, arriveBy, km, now);
        var tp = TripService.part(fTrain, TRAIN_WAIT, "도착 기준 기차");
        TrainResult tr = tp.value();
        Train train;
        if (!railFar) {
            train = new Train(null, false, null, null, List.of(), List.of(), null,
                    "가까운 거리(" + Math.round(km * 10) / 10.0 + "km)라 기차 비교는 하지 않습니다.");
        } else if (tr == null) {
            train = new Train(null, false, null, null, List.of(), List.of(), null, "기차 여정을 계산하고 있습니다.");
        } else {
            train = tr.train();
        }
        boolean pending = tp.incomplete() || (tr != null && tr.pending()) || car.pending();
        return new Arrival(from, to, arriveBy, confidence, now, train, car.car(), summary(train, car.car(), confidence), ASSUMPTIONS, pending, "MISS");
    }

    record TrainResult(Train train, boolean pending) {}

    record CarResult(Car car, boolean pending) {}

    TrainResult train(Place from, Place to, OffsetDateTime arriveBy, double confidence, Integer accessMin, OffsetDateTime now) {
        var plan = journeys.latestPlan(from, to, arriveBy, accessMin, now);
        if (plan.candidates().isEmpty()) {
            return new TrainResult(new Train(null, false, null, null, List.of(), List.of(), plan.referenceDate(), plan.note()), plan.pending());
        }
        // 지연 분포는 운행 기록의 가장 최근 30일 — 시간표 기준일(같은 요일 · 공휴일 제외)은 몇 주 전일 수 있어 최근 지연을 놓친다
        LocalDate end = rail.latestDate().orElse(LocalDate.parse(plan.referenceDate()));
        String basis = "최근 30일 실제 운행(" + end.minusDays(29) + " ~ " + end + ")";
        Map<String, DelayDistribution> dists = new HashMap<>();   // 역 쌍 · 열차 → 지연 분포 (후보끼리 같은 구간이 많다)
        record Scored(RailJourneyService.LatestCandidate c, ArrivalOdds.Odds odds, List<LegRisk> risks) {}
        List<Scored> scored = new ArrayList<>();
        for (var c : plan.candidates()) {
            var legs = c.journey().legs();
            List<ArrivalOdds.Step> steps = new ArrayList<>();
            List<LegRisk> risks = new ArrayList<>();
            for (int i = 0; i < legs.size(); i++) {
                var l = legs.get(i);
                var dist = dists.computeIfAbsent(l.fromCode() + ">" + l.toCode() + ":" + l.trnNo(), k -> DelayDistribution.of(
                        rail.delaySamples30d(l.fromCode(), l.toCode(), end, List.of(l.trnNo())).getOrDefault(l.trnNo(), List.of())));
                boolean last = i == legs.size() - 1;
                long allowance = last
                        ? Duration.between(l.arr(), arriveBy.minusMinutes(c.journey().egress().minutes())).toMinutes()
                        : Duration.between(l.arr(), legs.get(i + 1).dep()).toMinutes() - RailJourneyService.BOARDING_BUFFER_MIN;
                steps.add(new ArrivalOdds.Step(dist, allowance));
                risks.add(new LegRisk(l.trnNo(), last ? "ARRIVAL" : "TRANSFER", (int) allowance, dist.countWithin(allowance), dist.n()));
            }
            scored.add(new Scored(c, ArrivalOdds.of(steps), risks));
        }
        // 신뢰 수준 이상 중 가장 늦게 떠나는 것 (후보는 늦은 순) — 없으면 기록이 충분한 것 중 확률이 가장 높은 것, 그것도 없으면 가장 늦은 것
        Comparator<Scored> surest = Comparator.comparingDouble(s -> s.odds().probability());
        Scored pick = scored.stream().filter(s -> s.odds().meets(confidence)).findFirst()
                .or(() -> scored.stream().filter(s -> s.odds().known() && s.odds().n() >= ArrivalOdds.MIN_SAMPLES_FOR_PERCENT).max(surest))
                .orElse(scored.getFirst());
        boolean meets = pick.odds().meets(confidence);
        List<Alternative> alts = scored.stream().filter(s -> s != pick).limit(ALTERNATIVES)
                .map(s -> new Alternative(s.c().latestDepart(), s.c().journey().arriveAt(), s.c().journey().transfers(), odds(s.odds(), basis)))
                .toList();
        JourneyDtos.Journey j = pick.c().journey();
        boolean thin = !pick.odds().known() || pick.odds().n() < ArrivalOdds.MIN_SAMPLES_FOR_PERCENT;
        String note = meets ? plan.note()
                : thin ? "최근 30일 기록이 " + ArrivalOdds.MIN_SAMPLES_FOR_PERCENT + "회 미만이라 " + Math.round(confidence * 100)
                        + "% 판단을 하지 않고, 기한 안에 닿는 가장 늦은 열차를 보여 줍니다. " + plan.note()
                : Math.round(confidence * 100) + "% 를 만족하는 열차가 없어, 기록상 가장 확실한 열차를 보여 줍니다. " + plan.note();
        return new TrainResult(new Train(pick.c().latestDepart(), meets, odds(pick.odds(), basis), j, pick.risks(), alts,
                plan.referenceDate(), note), plan.pending());
    }

    static Odds odds(ArrivalOdds.Odds o, String basis) {
        return new Odds(o.percent(), o.within(), o.n(), o.allObservedWithin(), basis);
    }

    CarResult car(Place from, Place to, OffsetDateTime arriveBy, double km, OffsetDateTime now) {
        String basis = "카카오 미래 운행 정보(예측 소요). 예측 오차 근거가 준비되지 않아 확률은 내지 않습니다.";
        if (!mobility.enabled()) return new CarResult(new Car(null, null, false, 0, basis, "카카오 키가 없어 계산하지 않습니다."), false);
        AtomicBoolean failed = new AtomicBoolean();
        LatestDeparture.Eta eta = t -> {
            OffsetDateTime at = OffsetDateTime.ofInstant(Instant.ofEpochSecond(t), Times.KST);
            var e = mobility.futureEta(from.lat(), from.lon(), to.lat(), to.lon(), at);
            if (e.isPresent()) return e.get().durationSec();
            if (!mobility.pending(from.lat(), from.lon(), to.lat(), to.lon(), at, false)) failed.set(true);
            return null;
        };
        int guessSec = (int) Math.round(km * 1.3 / 70 * 3600);   // 처음 짐작: 직선 × 1.3 ÷ 70km/h — 이후는 실제 예측으로 좁힌다
        // 카카오는 1분 뒤부터의 미래 시각만 받는다 → 지금 + 2분 이후 칸부터
        var r = LatestDeparture.search(eta, arriveBy.toEpochSecond(), now.plusMinutes(2).toEpochSecond(), guessSec, CAR_MAX_CALLS);
        boolean pending = r.pending() && !failed.get();
        OffsetDateTime dep = r.depart() == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(r.depart()), Times.KST);
        String note = dep != null ? null
                : failed.get() ? "카카오 예측을 받지 못했습니다."
                : pending ? "카카오 예측을 받고 있습니다."
                : !r.feasible() ? "지금 떠나도 예측 소요로는 기한 안에 도착하지 못합니다."
                : "예측 조회 횟수 안에 마지막 출발 시각을 좁히지 못했습니다.";
        return new CarResult(new Car(dep, r.durationSec(), r.feasible(), r.calls(), basis, note), pending);
    }

    static String summary(Train t, Car c, double confidence) {
        int pct = (int) Math.round(confidence * 100);
        boolean trainOk = t.latestDepart() != null && t.meetsConfidence();
        if (trainOk && c.latestDepart() != null) {
            long diff = Duration.between(c.latestDepart(), t.latestDepart()).toMinutes();
            if (diff > 0) return "기차로 가면 " + diff + "분 늦게 떠나도 됩니다 (기차 " + pct + "% 기준 · 자동차는 예측 소요 기준).";
            if (diff < 0) return "자동차로 가면 " + (-diff) + "분 늦게 떠나도 됩니다 (자동차는 예측 소요 기준 · 확률 없음).";
            return "기차와 자동차의 마지막 출발 시각이 같습니다.";
        }
        String trainMiss = t.odds() != null && t.odds().n() < ArrivalOdds.MIN_SAMPLES_FOR_PERCENT
                ? "기차는 최근 기록이 " + ArrivalOdds.MIN_SAMPLES_FOR_PERCENT + "회 미만이라 확률 판단을 하지 않습니다"
                : "기차는 " + pct + "% 를 만족하는 열차가 없습니다";
        if (trainOk) return "기차는 늦어도 " + t.latestDepart().format(Times.HM) + "에 떠나면 됩니다 (" + pct + "% 기준).";
        if (c.latestDepart() != null) {
            return "자동차는 늦어도 " + c.latestDepart().format(Times.HM) + "에 떠나면 됩니다 (예측 소요 기준)"
                    + (t.latestDepart() != null ? " · " + trainMiss + "." : ".");
        }
        return t.latestDepart() != null ? trainMiss + "." : "기한 안에 도착하는 방법을 아직 찾지 못했습니다.";
    }
}
