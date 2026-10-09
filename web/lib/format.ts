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

/** 어제까지 days 일 (KST 날짜) — 운행 정보는 하루가 지나야 확정된다. 지금 시각에 따라 달라지므로 미리 그린 HTML 에 넣지 않는다 */
export function daysUntilYesterday(now: Date, days: number): { from: string; to: string } {
  return { from: ymd(new Date(now.getTime() - days * 86400_000)), to: ymd(new Date(now.getTime() - 86400_000)) };
}

export const DOW = ["", "월", "화", "수", "목", "금", "토", "일"];
/** 요일별 집계 키 → 이름. 'H' = 공휴일(한국천문연구원 특일 정보) */
export const dowLabel = (key: string) => (key === "H" ? "공휴일" : DOW[Number(key)] ?? key);
export const DIR_LABEL: Record<string, string> = { DN: "하행", UP: "상행" };

export const pm25Label = (g: number | null | undefined) =>
  g === null || g === undefined ? DASH : ["", "좋음", "보통", "나쁨", "매우나쁨"][g] ?? String(g);

/** 예측 방법 이름 — 영어 · 기호 대신 무엇을 하는지 드러나는 말 (M0 · M1 · persistence 는 API 의 열쇠 이름) */
export const MODEL_LABEL: Record<string, string> = { M0: "평소 값", M1: "평소 값 + 지금 차이", persistence: "지금 값 그대로" };
