package com.roadrail.domain;

import com.roadrail.domain.RouteGeometry.Geometry;
import com.roadrail.domain.RouteGeometry.RoadPath;
import com.roadrail.domain.RouteGeometry.TrafficRun;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 경로 좌표 줄이기 + 구간별 소통: 지도에서 정체 · 사고 구간을 다른 색으로 그리기 위한 좌표 범위 */
class RouteGeometryTest {

    /** 동쪽으로 곧게 가는 좌표 n 개 (경도 0.001 ≈ 90m) */
    static List<double[]> straight(double lat, double lon, int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new double[]{lat, lon + i * 0.001});
        return out;
    }

    /** 구간들이 경로 전체를 빈틈없이 덮는지: 0 에서 시작해 끝 점에서 끝나고, 이웃 구간은 경계 점을 함께 쓴다 */
    static void assertCovers(Geometry g) {
        List<TrafficRun> t = g.traffic();
        assertThat(t).isNotEmpty();
        assertThat(t.getFirst().from()).isZero();
        assertThat(t.getLast().to()).isEqualTo(g.path().size() - 1);
        for (int i = 0; i < t.size(); i++) {
            assertThat(t.get(i).to()).isGreaterThan(t.get(i).from());
            if (i > 0) assertThat(t.get(i).from()).isEqualTo(t.get(i - 1).to());
        }
    }

    @Test
    void sameTrafficRoadsMergeAndBoundariesAreShared() {
        var a = straight(37.50, 127.000, 5);                  // 원활
        var b = straight(37.50, 127.004, 5);                  // 원활 — 앞 도로 끝 점과 같은 점으로 시작
        var c = straight(37.50, 127.008, 5);                  // 정체
        var d = straight(37.50, 127.012, 5);                  // 사고
        var g = RouteGeometry.build(List.of(new RoadPath(4, 360, a), new RoadPath(4, 360, b), new RoadPath(1, 360, c), new RoadPath(6, 360, d)), 400);

        assertThat(g.traffic()).extracting(TrafficRun::traffic).containsExactly("원활", "정체", "사고");
        assertThat(g.traffic()).extracting(TrafficRun::distanceM).containsExactly(720, 360, 360);   // 합친 도로는 길이도 더한다
        assertCovers(g);
        // 곧은 길의 중간 점은 버리고 구간 경계만 남는다: 시작 · 원활|정체 · 정체|사고 · 끝
        assertThat(g.path()).hasSize(4);
        assertThat(g.path().get(1)[1]).isCloseTo(127.008, within(1e-9));
        assertThat(g.path().get(2)[1]).isCloseTo(127.012, within(1e-9));
    }

    @Test
    void cornersSurviveSimplification() {
        // ㄱ 자 경로 — 꺾이는 점은 허용 오차보다 훨씬 멀어 남아야 한다
        List<double[]> pts = new ArrayList<>(straight(37.50, 127.00, 11));
        for (int i = 1; i <= 10; i++) pts.add(new double[]{37.50 + i * 0.001, 127.01});
        var g = RouteGeometry.build(List.of(new RoadPath(4, 1800, pts)), 400);
        assertThat(g.path()).hasSize(3);
        assertThat(g.path().get(1)[0]).isCloseTo(37.50, within(1e-9));
        assertThat(g.path().get(1)[1]).isCloseTo(127.01, within(1e-9));
    }

    @Test
    void longWigglyRouteIsBoundedAndKeepsEveryRunBoundary() {
        // 20,000 점 · 소통이 40번 바뀌는 굽은 경로 → 400 점 이하, 구간 40개가 그대로
        List<RoadPath> roads = new ArrayList<>();
        int[] states = {4, 3, 2, 1, 6};
        double lon = 126.5;
        for (int r = 0; r < 40; r++) {
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                pts.add(new double[]{36.0 + 0.01 * Math.sin((r * 500 + i) / 15.0), lon});
                lon += 0.0002;
            }
            roads.add(new RoadPath(states[r % states.length], 9000, pts));
        }
        var g = RouteGeometry.build(roads, 400);
        assertThat(g.path().size()).isLessThanOrEqualTo(400);
        assertThat(g.traffic()).hasSize(40);
        assertCovers(g);
        assertThat(g.path().getFirst()).containsExactly(roads.getFirst().points().getFirst());
        assertThat(g.path().getLast()).containsExactly(roads.getLast().points().getLast());
    }

    @Test
    void significanceMatchesDouglasPeuckerAtThatTolerance() {
        // 중요도 > e 인 점만 남기면 허용 오차 e 의 DP 결과와 같다 — 부모가 버려지면 자식도 버려진다
        List<double[]> pts = List.of(new double[]{37.0, 127.0}, new double[]{37.0001, 127.001}, new double[]{37.0, 127.002},
                new double[]{37.003, 127.003}, new double[]{37.0, 127.004});
        double[] s = RouteGeometry.significance(pts);
        assertThat(s[0]).isInfinite();
        assertThat(s[4]).isInfinite();
        assertThat(s[3]).isGreaterThan(300);                  // 가장 먼 점(약 330m)
        assertThat(s[1]).isLessThan(20);                      // 11m 쯤 벗어난 점
        assertThat(s[1]).isLessThanOrEqualTo(s[3]);           // 자식은 부모보다 크지 않다
    }

    @Test
    void emptyAndUnknownInputs() {
        assertThat(RouteGeometry.build(List.of(), 400)).isEqualTo(Geometry.EMPTY);
        assertThat(RouteGeometry.build(List.of(new RoadPath(4, 0, List.of())), 400)).isEqualTo(Geometry.EMPTY);
        var g = RouteGeometry.build(List.of(new RoadPath(9, 180, straight(37.5, 127.0, 3))), 400);
        assertThat(g.traffic()).extracting(TrafficRun::traffic).containsExactly("정보 없음");
        assertCovers(g);
    }
}
