package com.roadrail.corridor.app;

import com.roadrail.shared.ApiException;
import com.roadrail.corridor.data.CorridorRepository;
import com.roadrail.corridor.model.CorridorDtos.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CorridorService {
    private final CorridorRepository repo;

    public CorridorService(CorridorRepository repo) { this.repo = repo; }

    public void require(String id) {
        if (!repo.isActive(id)) throw ApiException.corridorNotFound(id);
    }

    public String name(String id) {
        return repo.name(id);
    }

    /** 두 지점과 맞는 길 · 방향 */
    public record Match(String corridorId, String direction) {}

    /** 두 지점이 운영 중인 길의 끝(출발 · 도착 도시 역)과 각각 30km 안이면 그 길 · 방향 — 여럿이면 가장 가까운 것 */
    public Optional<Match> matchByEnds(double fromLat, double fromLon, double toLat, double toLon) {
        return repo.matchByEnds(fromLat, fromLon, toLat, toLon).map(m -> new Match(m.corridorId(), m.direction()));
    }

    public static String dir(String d) {
        if (d == null || !(d.equals("DN") || d.equals("UP"))) throw ApiException.invalid("dir 는 DN(하행) 또는 UP(상행) 입니다.");
        return d;
    }

    public List<Corridor> list() {
        var base = repo.activeCorridors();
        // 길 · 방향마다 첫 구간의 시작 영업소 + 각 구간의 끝 영업소, 거리는 구간 합
        Map<String, Map<String, List<Point>>> paths = new LinkedHashMap<>();
        Map<String, Map<String, Double>> dist = new LinkedHashMap<>();
        for (var seg : repo.roadSegments()) {
            String cid = seg.corridorId(), d = seg.direction();
            List<Point> pts = paths.computeIfAbsent(cid, k -> new LinkedHashMap<>()).computeIfAbsent(d, k -> new ArrayList<>());
            if (pts.isEmpty()) pts.add(seg.start());
            pts.add(seg.end());
            dist.computeIfAbsent(cid, k -> new LinkedHashMap<>()).merge(d, seg.distanceKm(), Double::sum);
        }
        Map<String, Map<String, RailPair>> rails = repo.railPairsByCorridor();
        Map<String, List<EnvPoint>> envs = repo.envPointsByCorridor();
        List<Corridor> out = new ArrayList<>();
        for (var b : base) {
            String id = b.id();
            Map<String, RoadPath> road = new LinkedHashMap<>();
            for (var e : paths.getOrDefault(id, Map.of()).entrySet()) {
                double km = dist.get(id).get(e.getKey());
                road.put(e.getKey(), new RoadPath(e.getValue().size() - 1, Math.round(km * 10) / 10.0, e.getValue()));
            }
            out.add(new Corridor(id, b.name(), b.originCity(), b.destCity(), road, rails.getOrDefault(id, Map.of()),
                    envs.getOrDefault(id, List.of())));
        }
        return out;
    }
}
