/**
 * API 클라이언트 — React 에 의존하지 않는다(단위 테스트는 node:test). 화면은 주소를 직접 만들지 않고 아래 `api` 로 만든다.
 * 모든 요청은 같은 출처의 /api/v1 프록시(pages/api/v1)를 거친다.
 */
import type { ApiError, Dir, Place } from "../types";

export class HttpError extends Error {
  readonly status: number;
  readonly body: ApiError | null;
  constructor(status: number, body: ApiError | null) {
    super(body?.message ?? `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

export async function getJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, { ...init, headers: { Accept: "application/json", ...(init?.headers ?? {}) } });
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // JSON 이 아닌 본문(프록시 · 서버의 HTML 오류 페이지 등) — 오류면 상태 코드로 알리고, 성공인데 JSON 이 아니면 그대로 알린다
    if (res.ok) throw new Error("응답을 해석할 수 없습니다 (JSON 아님)");
  }
  if (!res.ok) throw new HttpError(res.status, body as ApiError | null);
  return body as T;
}

/** 쿼리 문자열 — 빈 값(undefined · null · "")은 뺀다 */
export const qs = (p: Record<string, string | number | undefined | null>) =>
  Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");

const BASE = "/api/v1";
const seg = encodeURIComponent;  // URL 에서 온 값(길 id · 작업 이름)은 경로 조각으로 인코딩

type Point = Pick<Place, "lat" | "lon" | "name"> & { stationCode?: string | null };

/** 엔드포인트 주소 — 공개 API 의 경로 · 매개변수 이름은 그대로 */
export const api = {
  corridors: () => `${BASE}/corridors`,
  trip: (from: Point, to: Point, departIn: number, accessMin?: string | number) => `${BASE}/trip?${qs({
    fromLat: from.lat, fromLon: from.lon, fromName: from.name, fromStation: from.stationCode,
    toLat: to.lat, toLon: to.lon, toName: to.name, toStation: to.stationCode, departIn, accessMin })}`,
  roadRoute: (from: Point, to: Point, departIn: number) => `${BASE}/road/route?${qs({
    fromLat: from.lat, fromLon: from.lon, fromName: from.name, toLat: to.lat, toLon: to.lon, toName: to.name, departIn })}`,
  placesSearch: (term: string) => `${BASE}/places/search?${qs({ q: term })}`,
  stations: (p: { q?: string; limit: number; sort?: "name" }) => `${BASE}/stations?${qs({ limit: p.limit, sort: p.sort, q: p.q })}`,
  railPunctuality: (p: { dep: string; arr: string; from: string; to: string; thresholdMin: number; groupBy: "train" | "dow" | "hour" }) =>
    `${BASE}/rail/od/punctuality?${qs(p)}`,
  railTrains: (p: { dep: string; arr: string; date?: string | null }) => `${BASE}/rail/od/trains?${qs(p)}`,
  corridorSeries: (cid: string, p: { dir: Dir; agg: string; from: string; to: string }) => `${BASE}/corridors/${seg(cid)}/road/series?${qs(p)}`,
  corridorBaseline: (cid: string, dir: Dir) => `${BASE}/corridors/${seg(cid)}/road/baseline?${qs({ dir })}`,
  corridorForecast: (cid: string, dir: Dir, horizons: number[]) =>
    `${BASE}/corridors/${seg(cid)}/road/forecast?dir=${dir}&horizons=${horizons.join(",")}`,
  opsStatus: () => `${BASE}/ops/collect-status`,
  /** 오류 상세까지 — X-Admin-Token 필요(공개 경로는 상세를 비운다) */
  adminOpsStatus: () => `${BASE}/admin/collect-status`,
  runJob: (job: string) => `${BASE}/admin/jobs/${seg(job)}/run`,
};

/** 관리 명령: 작업 한 번 실행 요청 — 토큰은 머리글로만(주소 · 본문에 넣지 않음) */
export const runJob = (job: string, token: string) =>
  getJson<{ requestId: string }>(api.runJob(job), { method: "POST", headers: { "X-Admin-Token": token } });

/** 오류를 사람이 읽을 문장으로 — 서버 오류 코드가 있으면 앞에 */
export const errorText = (e: unknown) => (e instanceof HttpError ? `${e.body?.code ?? e.status}: ${e.message}` : String(e));
