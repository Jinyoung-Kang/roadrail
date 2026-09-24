package com.roadrail.service;

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
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    public RoadRouteService(KakaoMobilityClient kakao, TripService trips) {
        this.kakao = kakao;
        this.trips = trips;
    }

    public Analysis analyze(TripDtos.Place from, TripDtos.Place to, int departIn) {
        OffsetDateTime depart = Times.alignTo5Min(Times.now()).plusMinutes(departIn);
        var fRec = CompletableFuture.supplyAsync(() -> kakao.route(from.lat(), from.lon(), to.lat(), to.lon(), depart, null, true), exec);
        var fAvoid = CompletableFuture.supplyAsync(() -> kakao.route(from.lat(), from.lon(), to.lat(), to.lon(), depart, "motorway", true), exec);
        List<CompletableFuture<ProfilePoint>> fProfile = new ArrayList<>();
        for (int off : PROFILE_OFFSETS_MIN) {
            OffsetDateTime t = depart.plusMinutes(off);
            fProfile.add(CompletableFuture.supplyAsync(() -> {
                var r = kakao.route(from.lat(), from.lon(), to.lat(), to.lon(), t, null, false);
                return new ProfilePoint(t, off, r == null ? null : r.durationSec());
            }, exec));
        }
        var rec = TripService.join(fRec, Duration.ofSeconds(8));
        var avoid = TripService.join(fAvoid, Duration.ofSeconds(8));
        List<ProfilePoint> profile = fProfile.stream().map(f -> TripService.join(f, Duration.ofSeconds(8))).filter(Objects::nonNull).toList();
        ProfilePoint best = profile.stream().filter(p -> p.durationSec() != null).min(Comparator.comparingInt(ProfilePoint::durationSec)).orElse(null);
        var obs = trips.observed(from, to, depart, Times.now());
        return new Analysis(from, to, Math.round(KmaGrid.km(from.lat(), from.lon(), to.lat(), to.lon()) * 10) / 10.0, depart,
                summarize("추천 경로", rec), summarize("고속도로 회피", avoid), profile, best,
                obs == null ? null : new Monitored(obs.corridorId(), obs.corridorName(), obs.direction()),
                "카카오 미래 운행 정보(출발 시각 기준 예측). 카카오 응답에 도로 등급이 없어 이름에 '고속도로'가 있는 구간만 고속도로로 구분하고, 국도·지방도·시내 도로는 '그 외 도로'로 함께 표시합니다.");
    }

    static RouteSummary summarize(String label, KakaoMobilityClient.Route r) {
        if (r == null) return new RouteSummary(label, null, null, List.of(), List.of(), List.of(), List.of());
        List<RoadRun> runs = new ArrayList<>();
        for (var road : r.roads()) {
            String name = road.name() == null || road.name().isBlank() ? "(이름 없는 도로)" : road.name();
            RoadRun last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && last.name().equals(name)) {
                int d = last.distanceM() + road.distanceM(), t = last.durationSec() + road.durationSec();
                String traffic = worse(last.traffic(), RoadClass.traffic(road.trafficState()));
                runs.set(runs.size() - 1, new RoadRun(name, last.type(), d, t, speed(d, t), traffic));
            } else {
                runs.add(new RoadRun(name, RoadClass.of(name), road.distanceM(), road.durationSec(),
                        speed(road.distanceM(), road.durationSec()), RoadClass.traffic(road.trafficState())));
            }
        }
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (String t : List.of(RoadClass.MOTORWAY, RoadClass.OTHER)) agg.put(t, new int[2]);
        for (var run : runs) {
            agg.get(run.type())[0] += run.distanceM();
            agg.get(run.type())[1] += run.durationSec();
        }
        int total = Math.max(runs.stream().mapToInt(RoadRun::distanceM).sum(), 1);
        List<Share> shares = new ArrayList<>();
        agg.forEach((k, v) -> { if (v[0] > 0) shares.add(new Share(k, v[0], v[1], Math.round(v[0] * 1000.0 / total) / 1000.0)); });
        List<RoadRun> slow = runs.stream().filter(x -> ("정체".equals(x.traffic()) || "지체".equals(x.traffic()) || "사고".equals(x.traffic()))
                && x.distanceM() >= 300).sorted(Comparator.comparingInt(RoadRun::distanceM).reversed()).limit(10).toList();
        List<RoadRun> major = runs.stream().filter(x -> x.distanceM() >= 1000 || RoadClass.MOTORWAY.equals(x.type())).toList();
        return new RouteSummary(label, r.durationSec(), r.distanceM(), r.path(), shares, major, slow);
    }

    private static Double speed(int m, int sec) { return sec <= 0 ? null : Math.round(m / (double) sec * 3.6 * 10) / 10.0; }

    private static final List<String> ORDER = List.of("사고", "정체", "지체", "서행", "원활", "정보 없음");

    private static String worse(String a, String b) { return ORDER.indexOf(a) <= ORDER.indexOf(b) ? a : b; }
}
