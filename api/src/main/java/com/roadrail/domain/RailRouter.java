package com.roadrail.domain;

import java.util.*;

/**
 * 철도 환승 경로 탐색 — CSA (Connection Scan Algorithm, Dibbelt et al. 2013) 의 가장 이른 도착 변형.
 * <ul>
 *   <li>연결(connection) = 한 열차가 역 A 를 출발해 바로 다음 정차역 B 에 도착하는 한 구간. 출발 시각 순으로 한 번 훑는다.</li>
 *   <li>출발 후보 역마다 '탈 수 있는 시각'(어디서→역 이동 + 승차 여유)을, 도착 후보 역마다 '역→어디로 시간'을 받는다.</li>
 *   <li>같은 열차를 계속 타는 데는 벌점이 없고, 다른 열차로 갈아타려면 {@code transferSec} 이상 필요하다.</li>
 *   <li>결과는 (도착 + 역에서 이동)이 가장 이른 여정 한 개. 다음 여정은 첫 출발 이후로 다시 부른다.</li>
 * </ul>
 * 시각은 epoch 초. 순수 함수 — DB · 시간대와 무관하게 테스트한다 (RailRouterTest).
 */
public final class RailRouter {
    private RailRouter() {}

    /** 한 정차: arr/dep 는 없을 수 있다 (시발역 도착 없음, 종착역 출발 없음) */
    public record Stop(String trip, int seq, String stn, Long arr, Long dep) {}

    public record Connection(String trip, String from, String to, long dep, long arr) {}

    /** ready = 이 역에서 열차에 탈 수 있는 가장 이른 시각 */
    public record Origin(String stn, long ready) {}

    public record Dest(String stn, long egressSec) {}

    public record Leg(String trip, String from, String to, long dep, long arr) {}

    public record Journey(List<Leg> legs, String originStn, String destStn, long arrival, long finalArrival) {
        public long departure() { return legs.getFirst().dep(); }

        public int transfers() { return legs.size() - 1; }
    }

    /** 열차별 정차를 순서대로 이어 연결 목록을 만들고 출발 시각 순으로 정렬 */
    public static List<Connection> connections(Collection<Stop> stops) {
        Map<String, List<Stop>> byTrip = new HashMap<>();
        for (Stop s : stops) byTrip.computeIfAbsent(s.trip(), k -> new ArrayList<>()).add(s);
        List<Connection> out = new ArrayList<>();
        for (var e : byTrip.entrySet()) {
            List<Stop> l = e.getValue();
            l.sort(Comparator.comparingInt(Stop::seq));
            for (int i = 0; i + 1 < l.size(); i++) {
                Stop a = l.get(i), b = l.get(i + 1);
                Long dep = a.dep(), arr = b.arr() != null ? b.arr() : b.dep();
                if (dep == null || arr == null || arr < dep) continue;
                out.add(new Connection(e.getKey(), a.stn(), b.stn(), dep, arr));
            }
        }
        out.sort(Comparator.comparingLong(Connection::dep).thenComparingLong(Connection::arr));
        return out;
    }

    public static Optional<Journey> earliest(List<Connection> sorted, List<Origin> origins, List<Dest> dests, long transferSec) {
        if (origins.isEmpty() || dests.isEmpty()) return Optional.empty();
        Map<String, Long> canBoard = new HashMap<>();   // 이 역에서 (다른 열차로) 탈 수 있는 시각
        Map<String, Long> arrival = new HashMap<>();
        Map<String, Connection> tripEntry = new HashMap<>();
        Map<String, Connection[]> via = new HashMap<>(); // 역 → {탄 연결, 내린 연결}
        Set<String> originSet = new HashSet<>();
        for (Origin o : origins) {
            canBoard.merge(o.stn(), o.ready(), Math::min);
            originSet.add(o.stn());
        }
        Map<String, Long> egress = new HashMap<>();
        for (Dest d : dests) egress.merge(d.stn(), d.egressSec(), Math::min);
        long best = Long.MAX_VALUE;
        String bestStn = null;
        long minEgress = egress.values().stream().min(Long::compare).orElse(0L);
        for (Connection c : sorted) {
            if (c.dep() + minEgress >= best) break;  // 이후 연결로는 더 빨라질 수 없다
            boolean on = tripEntry.containsKey(c.trip());
            if (!on) {
                Long b = canBoard.get(c.from());
                if (b != null && b <= c.dep()) {
                    tripEntry.put(c.trip(), c);
                    on = true;
                }
            }
            if (!on) continue;
            if (c.arr() < arrival.getOrDefault(c.to(), Long.MAX_VALUE)) {
                arrival.put(c.to(), c.arr());
                via.put(c.to(), new Connection[]{tripEntry.get(c.trip()), c});
                canBoard.merge(c.to(), c.arr() + transferSec, Math::min);
                Long eg = egress.get(c.to());
                if (eg != null && c.arr() + eg < best) {
                    best = c.arr() + eg;
                    bestStn = c.to();
                }
            }
        }
        if (bestStn == null) return Optional.empty();
        LinkedList<Leg> legs = new LinkedList<>();
        String stn = bestStn;
        for (int guard = 0; guard < 12; guard++) {
            Connection[] v = via.get(stn);
            if (v == null) break;
            legs.addFirst(new Leg(v[0].trip(), v[0].from(), v[1].to(), v[0].dep(), v[1].arr()));
            stn = v[0].from();
            if (originSet.contains(stn)) break;
        }
        if (legs.isEmpty() || !originSet.contains(legs.getFirst().from())) return Optional.empty();
        return Optional.of(new Journey(List.copyOf(legs), legs.getFirst().from(), bestStn, arrival.get(bestStn), best));
    }

    /** 첫 여정 이후 출발하는 다음 여정들 (출발 시각이 겹치지 않게) */
    public static List<Journey> several(List<Connection> sorted, List<Origin> origins, List<Dest> dests, long transferSec, int n) {
        List<Journey> out = new ArrayList<>();
        List<Origin> cur = origins;
        for (int i = 0; i < n; i++) {
            var j = earliest(sorted, cur, dests, transferSec);
            if (j.isEmpty()) break;
            out.add(j.get());
            long after = j.get().departure() + 60;
            cur = origins.stream().map(o -> new Origin(o.stn(), Math.max(o.ready(), after))).toList();
        }
        return out;
    }
}
