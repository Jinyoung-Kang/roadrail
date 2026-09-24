/** 값이 없으면 없다고 말한다 — '—' (NFR-06) */
export const DASH = "—";

export function dur(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || Number.isNaN(sec)) return DASH;
  return durMin(Math.round(sec / 60));
}

export function durMin(min: number | null | undefined): string {
  if (min === null || min === undefined || Number.isNaN(min)) return DASH;
  const m = Math.round(min);
  if (Math.abs(m) < 60) return `${m}분`;
  const h = Math.floor(Math.abs(m) / 60), r = Math.abs(m) % 60;
  return `${m < 0 ? "-" : ""}${h}시간${r ? ` ${r}분` : ""}`;
}

/** 큰 숫자 표기용: [값, 단위] 쌍 — "1", "시간 47분" */
export function durParts(min: number | null | undefined): { big: string; unit: string } {
  if (min === null || min === undefined) return { big: DASH, unit: "" };
  const m = Math.round(min);
  if (m < 60) return { big: String(m), unit: "분" };
  const h = Math.floor(m / 60), r = m % 60;
  return { big: `${h}:${String(r).padStart(2, "0")}`, unit: "시간" };
}

export const pct = (v: number | null | undefined, digits = 0) =>
  v === null || v === undefined ? DASH : `${(v * 100).toFixed(digits)}%`;

export const signedPct = (v: number | null | undefined) =>
  v === null || v === undefined ? DASH : `${v > 0 ? "+" : ""}${v.toFixed(0)}%`;

export const num = (v: number | null | undefined, digits = 1) =>
  v === null || v === undefined ? DASH : v.toFixed(digits);

export function hm(iso: string | null | undefined): string {
  if (!iso) return DASH;
  const d = new Date(iso);
  return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
}

export function mdhm(iso: string | null | undefined): string {
  if (!iso) return DASH;
  const d = new Date(iso);
  return d.toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false,
    timeZone: "Asia/Seoul" });
}

export function ymd(d: Date): string {
  const k = new Date(d.toLocaleString("en-US", { timeZone: "Asia/Seoul" }));
  return `${k.getFullYear()}-${String(k.getMonth() + 1).padStart(2, "0")}-${String(k.getDate()).padStart(2, "0")}`;
}

export const DOW = ["", "월", "화", "수", "목", "금", "토", "일"];
export const DIR_LABEL: Record<string, string> = { DN: "하행", UP: "상행" };

export const pm25Label = (g: number | null | undefined) =>
  g === null || g === undefined ? DASH : ["", "좋음", "보통", "나쁨", "매우나쁨"][g] ?? String(g);

export const MODEL_LABEL: Record<string, string> = { M0: "M0 기준선", M1: "M1 기준선+편차", persistence: "지속" };
