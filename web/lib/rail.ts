/** 철도 분석 화면의 규칙 · 변환 — React 에 의존하지 않는다 */
import { dowLabel } from "./format.ts";
import type { Punctuality, Trains } from "./types";

export const PERIODS = [{ value: 30, label: "최근 30일" }, { value: 90, label: "최근 90일" }];
export const THRESHOLDS = [{ value: 3, label: "정시 ≤3분" }, { value: 5, label: "≤5분" }, { value: 10, label: "≤10분" }];
/** 기본 역 쌍 — 서울 → 대전 */
export const DEFAULT_PAIR = { dep: "3900023", arr: "3900073" };

type Item = Punctuality["items"][number];

/** 정시율(0~1) → 막대용 % (없으면 null — 0 으로 그리지 않음) */
const asPercent = (r: number | null) => (r == null ? null : r * 100);

/** 요일별 막대 — 공휴일은 따로('H') */
export const dowBars = (items: Item[]) => items.map((i) => ({ ...i, label: dowLabel(i.key), rate: asPercent(i.onTimeRate) }));

/** 출발 시간대별 막대 */
export const hourBars = (items: Item[]) => items.map((i) => ({ ...i, label: `${Number(i.key)}`, rate: asPercent(i.onTimeRate) }));

/** 이 날짜는 절반 이상이 계획 시각을 모르는 운행 — TAGO 시간표가 없는 날 */
export const mostlyUnplanned = (t: Trains | null) =>
  !!t && t.trains.length > 0 && t.trains.filter((x) => x.arrDelayMin == null).length >= t.trains.length / 2;

/** 시간표를 받는 중인지 — 세 묶음 중 하나라도 */
export const timetablePending = (...ps: (Punctuality | null)[]) => ps.some((p) => !!p?.timetablePending);
