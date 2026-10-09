/** 철도 분석 화면의 규칙 · 변환 — React 에 의존하지 않는다 */
import { dowLabel } from "./format.ts";
import type { DelayBands, Punctuality, Trains } from "./types";

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

/** 배상 기준 통계의 기간 — 20분 이상 지연은 드물어(전국 종착역 30일 약 0.6%) 기본 90일, 보관 전체는 API 상한인 1년 안 */
export const BAND_PERIODS = [{ value: 30, label: "최근 30일" }, { value: 90, label: "최근 90일" }, { value: 365, label: "최근 1년" }];
/** 배상 기준 시간(분) — API 규칙 DB-v1 과 같은 경계 */
export const BAND_THRESHOLDS = [20, 40, 60, 90, 120] as const;
/** 검증 운행이 이보다 적으면 퍼센트를 내지 않고 횟수만 (명세 R15) */
export const MIN_SAMPLES_FOR_PERCENT = 15;

type Threshold = (typeof BAND_THRESHOLDS)[number];
const countOf = (b: DelayBands, t: Threshold) => b[`ge${t}` as keyof DelayBands];

/** "1/72 · 1.4%" — 횟수가 먼저, 표본이 적으면 퍼센트 없음, 검증 운행이 없으면 null(모름) */
export function bandText(b: DelayBands | null | undefined, t: Threshold = 20): string | null {
  if (!b || b.verified === 0) return null;
  const n = countOf(b, t);
  if (b.verified < MIN_SAMPLES_FOR_PERCENT) return `${n}/${b.verified}`;
  return `${n}/${b.verified} · ${((n * 100) / b.verified).toFixed(1)}%`;
}

/** 배상 기준 표의 행 — 구간마다 이 역 쌍 · 전국 문구 */
export const bandRows = (pair: DelayBands | null | undefined, nation: DelayBands | null | undefined) =>
  BAND_THRESHOLDS.map((t) => ({ threshold: t, pair: bandText(pair, t), nation: bandText(nation, t) }));

/** 20분 이상이 한 번이라도 있었던 열차 — 많은 순, 같으면 표본이 많은 순 */
export const trainsOverThreshold = (items: Punctuality["items"]) =>
  items.filter((i) => (i.delayBands?.ge20 ?? 0) > 0)
    .sort((a, b) => b.delayBands.ge20 - a.delayBands.ge20 || b.delayBands.verified - a.delayBands.verified);
