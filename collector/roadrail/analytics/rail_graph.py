"""철도 선로 그래프와 역 쌍 경로 — 순수 함수 (tests/unit/test_rail_graph.py).

- 그래프: OSM way 의 연속 노드를 잇는 무방향 간선 (가중치 = 하버사인 km).
- 역 붙이기: 역마다 가상 노드를 만들어 반경 400m(없으면 1.2km) 안의 **모든** 선로 노드와 잇는다.
  역 하나에 여러 노선이 지나가는 경우 한 점에만 붙이면 엉뚱한 선에서 출발하기 때문.
- 경로: 출발역에서 다익스트라 한 번으로 그 역의 다음 정차역들을 모두 찾는다 (직선 × 2.2 + 5km 까지).
  다른 역의 가상 노드는 통과하지 않는다.
- 단순화: Douglas–Peucker (허용 오차 약 30m) 로 지도용 점 수를 줄인다.
실측(2026-09-25): 역 쌍 1,056개 모두 경로 · 선로 길이/직선 중앙값 1.12 · 계산 1.5초.
"""
from __future__ import annotations

import heapq
import math
from collections import defaultdict
from collections.abc import Iterable


def hav(a: tuple[float, float], b: tuple[float, float]) -> float:
    p = math.pi / 180
    h = math.sin((b[0] - a[0]) * p / 2) ** 2 + math.cos(a[0] * p) * math.cos(b[0] * p) * math.sin((b[1] - a[1]) * p / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


class RailGraph:
    def __init__(self, ways: Iterable[dict]):
        self.coord: dict = {}
        self.adj: dict = defaultdict(list)
        for w in ways:
            ns, g = w["nodes"], w["geometry"]
            for n, pt in zip(ns, g, strict=False):
                if pt:
                    self.coord[n] = (pt["lat"], pt["lon"])
            for a, b in zip(ns, ns[1:], strict=False):
                if a in self.coord and b in self.coord:
                    d = hav(self.coord[a], self.coord[b])
                    self.adj[a].append((b, d))
                    self.adj[b].append((a, d))
        self.grid: dict = defaultdict(list)
        for n, (la, lo) in self.coord.items():
            self.grid[(int(la * 100), int(lo * 100))].append(n)
        self.stations: dict[str, tuple[float, float]] = {}

    def _near(self, lat: float, lon: float, r_km: float) -> list[tuple[object, float]]:
        k = int(r_km * 1.2) + 1
        out = []
        for dx in range(-k, k + 1):
            for dy in range(-k, k + 1):
                for n in self.grid.get((int(lat * 100) + dx, int(lon * 100) + dy), ()):
                    d = hav((lat, lon), self.coord[n])
                    if d <= r_km:
                        out.append((n, d))
        return out

    def add_station(self, code: str, lat: float, lon: float) -> bool:
        near = self._near(lat, lon, 0.4) or self._near(lat, lon, 1.2)
        key = ("S", code)
        self.stations[code] = (lat, lon)
        self.coord[key] = (lat, lon)
        for n, d in near:
            self.adj[key].append((n, d))
            self.adj[n].append((key, d))
        return bool(near)

    def routes_from(self, src: str, targets: Iterable[str], factor: float = 2.2, slack_km: float = 5.0) -> dict[str, tuple[float, list]]:
        """src 역 → 각 target 역: (선로 길이 km, [(lat, lon), …])"""
        s = ("S", src)
        tset = {("S", t) for t in targets if t in self.stations and t != src}
        if s not in self.adj or not tset:
            return {}
        lim = max(hav(self.stations[src], self.stations[t[1]]) for t in tset) * factor + slack_km
        dist = {s: 0.0}
        prev: dict = {}
        pq = [(0.0, 0, s)]
        seq = 1
        out = {}
        while pq and tset:
            d, _, u = heapq.heappop(pq)
            if d > dist.get(u, math.inf):
                continue
            if d > lim:
                break
            if u in tset:
                tset.discard(u)
                path, x = [], u
                while x in prev or x == s:
                    path.append(self.coord[x])
                    if x == s:
                        break
                    x = prev[x]
                out[u[1]] = (d, path[::-1])
                continue
            if isinstance(u, tuple) and u != s:
                continue  # 다른 역의 가상 노드는 통과하지 않는다
            for v, w in self.adj[u]:
                nd = d + w
                if nd < dist.get(v, math.inf):
                    dist[v] = nd
                    prev[v] = u
                    heapq.heappush(pq, (nd, seq, v))
                    seq += 1
        return out


def simplify(pts: list[tuple[float, float]], eps_km: float = 0.03) -> list[tuple[float, float]]:
    """Douglas–Peucker (평면 근사, 위경도 → km)"""
    if len(pts) <= 2:
        return list(pts)
    lat0 = math.radians(pts[0][0])
    xy = [(p[1] * 111.32 * math.cos(lat0), p[0] * 110.57) for p in pts]

    def perp(i: int, a: int, b: int) -> float:
        (x, y), (x1, y1), (x2, y2) = xy[i], xy[a], xy[b]
        dx, dy = x2 - x1, y2 - y1
        if dx == dy == 0:
            return math.hypot(x - x1, y - y1)
        return abs(dy * x - dx * y + x2 * y1 - y2 * x1) / math.hypot(dx, dy)

    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        if b <= a + 1:
            continue
        i, dmax = max(((i, perp(i, a, b)) for i in range(a + 1, b)), key=lambda t: t[1])
        if dmax > eps_km:
            keep[i] = True
            stack += [(a, i), (i, b)]
    return [p for p, k in zip(pts, keep, strict=True) if k]
