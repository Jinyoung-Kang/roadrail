/** 도착 시각 기준 판단(AR-1 · AR-2)의 규칙 — 시각 선택지 · 주소 매개변수 · 확률 문구. React 에 의존하지 않는다 */
import type { ArrivalOdds, LegRisk } from "./types";

const KST_MS = 9 * 3600_000;
/** 서버 규칙과 같다: 지금 + 30분 ~ 지금 + 24시간, 5분 단위 */
export const MIN_LEAD_MIN = 30, MAX_LEAD_MIN = 24 * 60, STEP_MIN = 5;
export const MIN_SAMPLES_FOR_PERCENT = 15;
export const CONFIDENCE_OPTIONS = [80, 90, 95].map((v) => ({ value: v, label: `${v}%` }));
export const MODE_OPTIONS = [{ value: "depart", label: "출발 시각 기준" }, { value: "arrive", label: "도착 시각 기준" }] as const;
export const DAY_OPTIONS = [{ value: 0, label: "오늘" }, { value: 1, label: "내일" }];

export type Mode = "depart" | "arrive";
export interface ArrivalQuery { mode: Mode; day: 0 | 1; at: string | null; confidence: number }

/** 한국 시각 날짜(yyyy-MM-dd) — now 에서 day 일 뒤 */
export function kstDate(now: Date, day: number): string {
  return new Date(now.getTime() + KST_MS + day * 86400_000).toISOString().slice(0, 10);
}

/** "HHmm" 이 고른 날의 몇 시 몇 분인지 → epoch ms (한국 시각) */
export function atEpoch(now: Date, day: number, at: string): number {
  return Date.parse(`${kstDate(now, day)}T${at.slice(0, 2)}:${at.slice(2, 4)}:00+09:00`);
}

/** 고른 날의 도착 시각 선택지 — 5분 단위, 지금 + 30분 ~ 지금 + 24시간 안만 */
export function slotOptions(now: Date, day: number): { value: string; label: string }[] {
  const lo = now.getTime() + MIN_LEAD_MIN * 60_000, hi = now.getTime() + MAX_LEAD_MIN * 60_000;
  const out: { value: string; label: string }[] = [];
  for (let m = 0; m < 24 * 60; m += STEP_MIN) {
    const at = `${String(Math.floor(m / 60)).padStart(2, "0")}${String(m % 60).padStart(2, "0")}`;
    const t = atEpoch(now, day, at);
    if (t >= lo && t <= hi) out.push({ value: at, label: `${at.slice(0, 2)}:${at.slice(2)}` });
  }
  return out;
}

/** 기본 도착 시각 — 지금 + 2시간을 30분 단위로 올림 (오늘 안이 아니면 내일) */
export function defaultSlot(now: Date): { day: 0 | 1; at: string } {
  const t = Math.ceil((now.getTime() + 120 * 60_000) / 1_800_000) * 1_800_000;
  const k = new Date(t + KST_MS);
  const day = k.toISOString().slice(0, 10) === kstDate(now, 0) ? 0 : 1;
  return { day, at: `${String(k.getUTCHours()).padStart(2, "0")}${String(k.getUTCMinutes()).padStart(2, "0")}` };
}

/** 주소 매개변수(?by=arrive&d=0|1&at=1400&c=90) → 판단 기준. 고를 수 없는 값은 기본값으로 */
export function parseArrivalQuery(q: Record<string, string | string[] | undefined>): ArrivalQuery {
  const one = (k: string) => (Array.isArray(q[k]) ? q[k]![0] : (q[k] as string | undefined));
  const c = Number(one("c"));
  const at = one("at");
  return {
    mode: one("by") === "arrive" ? "arrive" : "depart",
    day: one("d") === "1" ? 1 : 0,
    at: at && /^([01]\d|2[0-3])[0-5]\d$/.test(at) && Number(at.slice(2)) % STEP_MIN === 0 ? at : null,
    confidence: CONFIDENCE_OPTIONS.some((o) => o.value === c) ? c : 90,
  };
}

/** 지금 고를 수 있는 (날, 시각) — 주소의 값이 범위를 벗어났으면(시간이 지나서) 기본값 */
export function resolveSlot(now: Date, q: ArrivalQuery): { day: 0 | 1; at: string } {
  if (q.at && slotOptions(now, q.day).some((o) => o.value === q.at)) return { day: q.day, at: q.at };
  return defaultSlot(now);
}

/** API 의 arriveBy — yyyy-MM-ddTHH:mm (한국 시각) */
export const arriveByParam = (now: Date, day: number, at: string) => `${kstDate(now, day)}T${at.slice(0, 2)}:${at.slice(2)}`;

/**
 * 확률 문구 (명세 R4 · R5 · R6): 15회 이상이면 퍼센트, 그보다 적으면 빈도만, 관측된 운행이 모두 기한 안이면 "100%" 대신 그대로.
 * 환승 여정은 구간마다 빈도(legRisks)를 쓴다.
 */
export function oddsText(odds: ArrivalOdds | null, risks: LegRisk[] = []): { main: string; sub: string } {
  if (!odds || odds.n === 0) return { main: "기록 없음", sub: "최근 30일 이 열차의 도착 기록이 없어 확률을 내지 않습니다" };
  const legs = risks.length > 1 ? risks.map((r) => `${r.kind === "TRANSFER" ? "환승" : "도착"} ${r.n}회 중 ${r.within}회`).join(" · ") : null;
  if (odds.allObservedWithin) return { main: `${odds.n}회 모두 기한 안`, sub: legs ?? `최근 30일 관측된 ${odds.n}회 모두 기한 안 도착 (100% 를 뜻하지 않습니다)` };
  if (odds.percent != null) return { main: `${odds.percent}%`, sub: legs ?? `최근 30일 ${odds.n}회 중 ${odds.within}회 기한 안 도착` };
  const few = `기록 ${MIN_SAMPLES_FOR_PERCENT}회 미만 — 확률 대신 빈도`;
  if (odds.within != null) return { main: `${odds.n}회 중 ${odds.within}회`, sub: few };
  return { main: "빈도만", sub: legs ? `${legs} · ${few}` : few };
}

/** a 가 b 보다 몇 분 늦은지 (둘 중 하나라도 없으면 null) */
export function minutesLater(a: string | null | undefined, b: string | null | undefined): number | null {
  if (!a || !b) return null;
  return Math.round((Date.parse(a) - Date.parse(b)) / 60_000);
}
