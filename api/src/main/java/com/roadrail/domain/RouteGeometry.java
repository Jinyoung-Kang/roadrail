package com.roadrail.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 자동차 경로 좌표를 지도에 보낼 만큼 줄이면서, 도로 구간별 소통(원활 · 서행 · 지체 · 정체 · 사고)을 좌표 번호 범위로 함께 남긴다.
 * 같은 소통이 이어지는 도로는 한 구간으로 합치고, 구간마다 Douglas–Peucker 로 줄인다 — 구간 경계 점은 늘 남는다.
 * (예전처럼 전체 좌표를 균등 추출하면 구간 경계가 사라져 색을 나눌 수 없고, 굽은 길도 직선으로 깎였다.)
 */
public final class RouteGeometry {
    /** 이보다 가까운(m) 점은 점 수에 여유가 있어도 버린다 — 곧은 길의 중복 점 */
    static final double MIN_TOLERANCE_M = 3.0;

    private RouteGeometry() {}

    /** 도로 하나: 카카오 소통 코드(traffic_state) · 길이(m) · 좌표 [[위도, 경도], …] */
    public record RoadPath(int trafficState, int distanceM, List<double[]> points) {}

    /** path[from..to](양끝 포함)가 같은 소통 · 길이(m, 카카오 도로 길이의 합) — 이웃한 구간은 경계 점 하나를 함께 쓴다 */
    public record TrafficRun(int from, int to, String traffic, int distanceM) {}

    public record Geometry(List<double[]> path, List<TrafficRun> traffic) {
        public static final Geometry EMPTY = new Geometry(List.of(), List.of());
    }

    /**
     * 도로들을 소통이 같은 구간으로 합치고, 전체 점이 maxPoints 를 넘지 않게 줄인다.
     * 구간 경계와 양끝 점은 항상 남기고, 나머지는 DP 중요도(그 점을 남기는 가장 큰 허용 오차)가 큰 순서로 남긴다 —
     * 허용 오차를 한 번에 정해 DP 를 여러 번 돌리지 않는다. 구간이 maxPoints 보다 많으면 경계 점만으로 넘칠 수 있다.
     */
    public static Geometry build(List<RoadPath> roads, int maxPoints) {
        List<String> labels = new ArrayList<>();
        List<Integer> meters = new ArrayList<>();
        List<List<double[]>> runs = new ArrayList<>();
        for (RoadPath road : roads) {
            if (road.points() == null || road.points().isEmpty()) continue;
            String label = RoadClass.traffic(road.trafficState());
            List<double[]> run;
            if (!runs.isEmpty() && labels.getLast().equals(label)) {
                run = runs.getLast();
                meters.set(meters.size() - 1, meters.getLast() + Math.max(road.distanceM(), 0));
            } else {
                run = new ArrayList<>();
                if (!runs.isEmpty()) run.add(runs.getLast().getLast());   // 경계 점을 이웃 구간과 함께 쓴다
                runs.add(run);
                labels.add(label);
                meters.add(Math.max(road.distanceM(), 0));
            }
            for (double[] p : road.points()) {
                if (run.isEmpty() || !same(run.getLast(), p)) run.add(p);
            }
        }
        if (runs.isEmpty()) return Geometry.EMPTY;

        List<double[]> significance = new ArrayList<>();   // 구간마다 점별 중요도 (양끝 = 무한대)
        List<Double> interior = new ArrayList<>();
        for (List<double[]> run : runs) {
            double[] s = significance(run);
            significance.add(s);
            for (int i = 1; i + 1 < s.length; i++) interior.add(s[i]);
        }
        int fixed = runs.size() + 1;                        // 구간 경계(공유) + 양끝
        int budget = Math.max(0, maxPoints - fixed);
        double threshold = MIN_TOLERANCE_M;
        if (interior.size() > budget) {
            double[] sorted = interior.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            threshold = Math.max(threshold, sorted[sorted.length - budget - 1]);   // 이 값보다 큰 것만 남긴다
        }

        List<double[]> path = new ArrayList<>();
        List<TrafficRun> traffic = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            List<double[]> run = runs.get(r);
            double[] s = significance.get(r);
            int from = path.isEmpty() ? 0 : path.size() - 1;
            for (int i = path.isEmpty() ? 0 : 1; i < run.size(); i++) {  // 0번은 앞 구간의 끝 점과 같다
                if (i == 0 || i == run.size() - 1 || s[i] > threshold) path.add(run.get(i));
            }
            int to = path.size() - 1;
            if (to > from) traffic.add(new TrafficRun(from, to, labels.get(r), meters.get(r)));
        }
        return new Geometry(List.copyOf(path), List.copyOf(traffic));
    }

    /**
     * Douglas–Peucker 중요도: 점 i 를 남기는 가장 큰 허용 오차(m). 허용 오차 e 로 DP 를 돌린 결과 = 중요도 &gt; e 인 점.
     * (부모 분할점보다 큰 값은 부모 값으로 줄인다 — 부모가 버려지면 그 안의 점은 검사되지도 않기 때문.) 재귀 대신 스택.
     */
    static double[] significance(List<double[]> pts) {
        int n = pts.size();
        double[] sig = new double[n];
        if (n == 0) return sig;
        sig[0] = Double.POSITIVE_INFINITY;
        sig[n - 1] = Double.POSITIVE_INFINITY;
        if (n < 3) return sig;
        double lat0 = Math.toRadians(pts.get(0)[0]);
        double kx = 111_320.0 * Math.cos(lat0), ky = 110_574.0;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = pts.get(i)[1] * kx; y[i] = pts.get(i)[0] * ky; }
        ArrayDeque<double[]> stack = new ArrayDeque<>();   // {처음, 끝, 부모 중요도}
        stack.push(new double[]{0, n - 1, Double.POSITIVE_INFINITY});
        while (!stack.isEmpty()) {
            double[] seg = stack.pop();
            int a = (int) seg[0], b = (int) seg[1];
            if (b - a < 2) continue;
            int best = -1;
            double far = -1;
            for (int i = a + 1; i < b; i++) {
                double d = segmentDistance(x[i], y[i], x[a], y[a], x[b], y[b]);
                if (d > far) { far = d; best = i; }
            }
            double s = Math.min(far, seg[2]);
            sig[best] = s;
            stack.push(new double[]{a, best, s});
            stack.push(new double[]{best, b, s});
        }
        return sig;
    }

    /** 점 (px, py) 와 선분 (ax, ay)–(bx, by) 사이 거리 */
    static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static boolean same(double[] a, double[] b) {
        return Arrays.equals(a, b);
    }
}
