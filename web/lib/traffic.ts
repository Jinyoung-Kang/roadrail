import type { MapLine } from "./map";
import { C } from "./palette";
import type { TrafficRun } from "./types";

/**
 * 자동차 경로의 느린 구간(카카오 소통: 지체 · 정체 · 사고) — 서버의 '느린 구간'(도로 분석) 과 같은 기준.
 * 색은 자동차 파랑 · 기차 주황과 한 지도에 함께 그려지므로 다섯 색을 함께 검증했다
 * (dataviz validate_palette, 모든 쌍: 색각 이상 ΔE ≥ 9.3 · 정상 시각 ≥ 16.2 · 표면 대비 ≥ 3:1).
 * 색만으로 구분하지 않게 사고는 점선, 느린 구간은 흰 테두리, 범례에 글자를 함께 둔다.
 */
export const SLOW_STATES = ["지체", "정체", "사고"] as const;
export type SlowState = (typeof SLOW_STATES)[number];
export const TRAFFIC_COLOR: Record<SlowState, string> = { 지체: "#c93a8c", 정체: "#9c1033", 사고: "#6b35a3" };
export const TRAFFIC_LABEL: Record<SlowState, string> = { 지체: "지체", 정체: "정체", 사고: "사고 · 통행 불가" };

export const isSlow = (t: string): t is SlowState => (SLOW_STATES as readonly string[]).includes(t);

/** 쓸 수 있는 구간만 — 서버가 준 번호가 경로 밖이면 버린다 (다른 캐시 형식 · 잘린 응답) */
function validRuns(path: [number, number][], traffic?: TrafficRun[] | null): TrafficRun[] {
  return (traffic ?? []).filter((r) => Number.isInteger(r.from) && Number.isInteger(r.to) && r.from >= 0 && r.to > r.from && r.to < path.length);
}

/**
 * 자동차 경로 선: 전체를 기본색 한 줄로 그리고, 느린 구간만 흰 테두리 위에 덧그린다.
 * 테두리를 모두 그린 뒤 색을 그려 이웃 구간의 테두리가 색을 덮지 않게 한다. 구간 정보가 없으면 기본 선 한 줄.
 */
export function carLines(path: [number, number][], traffic: TrafficRun[] | null | undefined,
                         base: { color?: string; weight?: number } = {}): MapLine[] {
  if (path.length < 2) return [];
  const weight = base.weight ?? 5;
  const casing: MapLine[] = [], slow: MapLine[] = [];
  for (const r of validRuns(path, traffic)) {
    if (!isSlow(r.traffic)) continue;
    const seg = path.slice(r.from, r.to + 1);
    casing.push({ path: seg, color: "#ffffff", weight: weight + 4 });
    slow.push({ path: seg, color: TRAFFIC_COLOR[r.traffic], weight: weight + 1, dashed: r.traffic === "사고" });
  }
  return [{ path, color: base.color ?? C.road, weight }, ...casing, ...slow];
}

/** 경로에 있는 느린 구간 — 소통별 길이(m)와 구간 수, 경로 전체 길이 */
export function slowSummary(path: [number, number][], traffic?: TrafficRun[] | null) {
  const runs = validRuns(path, traffic);
  const by = Object.fromEntries(SLOW_STATES.map((s) => [s, { meters: 0, count: 0 }])) as Record<SlowState, { meters: number; count: number }>;
  let total = 0;
  for (const r of runs) {
    total += r.distanceM;
    if (isSlow(r.traffic)) { by[r.traffic].meters += r.distanceM; by[r.traffic].count += 1; }
  }
  const present = SLOW_STATES.filter((s) => by[s].count > 0);
  return { runs, by, present, total };
}
