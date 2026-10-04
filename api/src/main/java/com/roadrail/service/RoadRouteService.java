package com.roadrail.service;

import com.roadrail.common.Futures;
import com.roadrail.external.KakaoMobilityClient;
import com.roadrail.common.Times;
import com.roadrail.domain.KmaGrid;
import com.roadrail.domain.RoadClass;
import com.roadrail.web.dto.RouteDtos.*;
import com.roadrail.web.dto.TripDtos;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 도로 분석 — 전국 임의의 두 지점. 카카오 미래 운행 정보 길찾기로
 *  (1) 추천 경로의 도로별 구성(고속도로 · 그 외 도로)과 느린 구간,
 *  (2) 고속도로 회피 경로 비교,
 *  (3) 출발 시각별 소요시간(지금 ~ 12시간 뒤)
 * 을 만든다. 두 지점이 수집 중인 길과 겹치면 고속도로 실측 분석으로 이어 준다. 결과 20분 캐시 · 새 조합당 호출 약 9건.
 */
@Service
public class RoadRouteService {
    static final int[] PROFILE_OFFSETS_MIN = {0, 60, 120, 180, 240, 360, 540, 720};
    private final KakaoMobilityClient kakao;
    private final TripService trips;
    private final ExecutorService exec;

    public RoadRouteService(KakaoMobilityClient kakao, TripService trips, ExecutorService exec) {
        this.exec = exec;
        this.kakao = kakao;
        this.trips = trips;
    }

    public Analysis analyze(TripDtos.Place from, TripDtos.Place to, int departIn) {
        OffsetDateTime depart = Times.alignTo5Min(Times.now()).plusMinutes(departIn);
        var fRec = CompletableFuture.supplyAsync(() -> kakao.route(from.lat(), from.lon(), to.lat(), to.lon(), depart, null, true), exec);
        var fAvoid = CompletableFuture.supplyAsync(() -> kakao.route(from.lat(), from.lon(), to.lat(), to.lon(), depart, "motorway", true), exec);
        List<CompletableFuture<ProfilePoint>> fProfile = new ArrayList<>();
        for (int off : PROFILE_OFFSETS_MIN) {
            if (off == 0) continue;   // 0분 = 추천 경로와 같은 출발 — 따로 부르지 않고 추천 경로의 소요를 쓴다
            OffsetDateTime t = depart.plusMinutes(off);
            fProfile.add(CompletableFuture.supplyAsync(() -> {
                var r = kakao.route(from.lat(), from.lon(), to.lat(), to.lon(), t, null, false);
                return new ProfilePoint(t, off, r == null ? null : r.durationSec());
            }, exec));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();  // 병렬 조회 11건이 마감을 공유 (건마다 8초씩 더해지지 않게)
        var rec = Futures.join(fRec, Futures.left(deadline));
        var avoid = Futures.join(fAvoid, Futures.left(deadline));
        List<ProfilePoint> profile = new ArrayList<>();
        profile.add(new ProfilePoint(depart, 0, rec == null ? null : rec.durationSec()));
        fProfile.stream().map(f -> Futures.join(f, Futures.left(deadline))).filter(Objects::nonNull).forEach(profile::add);
        ProfilePoint best = profile.stream().filter(p -> p.durationSec() != null).min(Comparator.comparingInt(ProfilePoint::durationSec)).orElse(null);
        var obs = trips.observed(from, to, depart, Times.now());
        return new Analysis(from, to, Math.round(KmaGrid.km(from.lat(), from.lon(), to.lat(), to.lon()) * 10) / 10.0, depart,
                summarize("추천 경로", rec), summarize("고속도로 회피", avoid), profile, best,
                obs == null ? null : new Monitored(obs.corridorId(), obs.corridorName(), obs.direction()),
                "카카오 미래 운행 정보(출발 시각 기준 예측). 카카오 응답에 도로 등급이 없어 이름에 '고속도로'가 있는 구간만 고속도로로 구분하고, 국도·지방도·시내 도로는 '그 외 도로'로 함께 표시합니다.");
    }

    static RouteSummary summarize(String label, KakaoMobilityClient.Route r) {
        if (r == null) return new RouteSummary(label, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
        List<RoadRun> runs = runs(r.roads(), false);
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (String t : List.of(RoadClass.MOTORWAY, RoadClass.OTHER)) agg.put(t, new int[2]);
        for (var run : runs) {
            agg.get(run.type())[0] += run.distanceM();
            agg.get(run.type())[1] += run.durationSec();
        }
        int total = Math.max(runs.stream().mapToInt(RoadRun::distanceM).sum(), 1);
        List<Share> shares = new ArrayList<>();
        agg.forEach((k, v) -> { if (v[0] > 0) shares.add(new Share(k, v[0], v[1], Math.round(v[0] * 1000.0 / total) / 1000.0)); });
        // 느린 구간은 이름과 소통이 모두 같은 도로끼리만 합친다 — 이름만으로 합치면 긴 고속도로 전체가 '정체'로 잡혔다
        // (실측: 서울역 → 대전역 '경부고속도로 정체 152.5km', 실제 정체는 6.8km)
        List<RoadRun> slow = runs(r.roads(), true).stream().filter(x -> SLOW.contains(x.traffic()) && x.distanceM() >= 300)
                .sorted(Comparator.comparingInt(RoadRun::distanceM).reversed()).limit(10).toList();
        List<RoadRun> major = runs.stream().filter(x -> x.distanceM() >= 1000 || RoadClass.MOTORWAY.equals(x.type())).toList();
        return new RouteSummary(label, r.durationSec(), r.distanceM(), r.path(), r.traffic(), shares, major, slow);
    }

    /**
     * 이어진 같은 이름의 도로를 한 줄로 합친다. byTraffic 이면 소통이 같을 때만 합친다(느린 구간 목록).
     * 합친 줄의 소통은 가장 나쁜 쪽, trafficM 은 그 소통인 길이.
     */
    static List<RoadRun> runs(List<KakaoMobilityClient.Road> roads, boolean byTraffic) {
        List<RoadRun> runs = new ArrayList<>();
        for (var road : roads) {
            String name = road.name() == null || road.name().isBlank() ? "(이름 없는 도로)" : road.name();
            String traffic = RoadClass.traffic(road.trafficState());
            RoadRun last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && last.name().equals(name) && (!byTraffic || last.traffic().equals(traffic))) {
                int d = last.distanceM() + road.distanceM(), t = last.durationSec() + road.durationSec();
                String w = worse(last.traffic(), traffic);
                int wm = last.traffic().equals(traffic) ? last.trafficM() + road.distanceM() : w.equals(traffic) ? road.distanceM() : last.trafficM();
                runs.set(runs.size() - 1, new RoadRun(name, last.type(), d, t, speed(d, t), w, wm));
            } else {
                runs.add(new RoadRun(name, RoadClass.of(name), road.distanceM(), road.durationSec(),
                        speed(road.distanceM(), road.durationSec()), traffic, road.distanceM()));
            }
        }
        return runs;
    }

    private static final Set<String> SLOW = Set.of("지체", "정체", "사고");

    private static Double speed(int m, int sec) { return sec <= 0 ? null : Math.round(m / (double) sec * 3.6 * 10) / 10.0; }

    private static final List<String> ORDER = List.of("사고", "정체", "지체", "서행", "원활", "정보 없음");

    private static String worse(String a, String b) { return ORDER.indexOf(a) <= ORDER.indexOf(b) ? a : b; }
}
