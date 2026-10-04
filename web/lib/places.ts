import type { Corridor, Place } from "./types";

/** URL 에 담는 장소: 이름~위도~경도~역코드 — 이름에 '~' 가 있을 수 있어 되읽을 때는 뒤의 세 칸을 뗀다 */
export function encodePlace(p: Place): string {
  return [p.name, p.lat.toFixed(5), p.lon.toFixed(5), p.stationCode ?? ""].join("~");
}

export function decodePlace(s: string | string[] | undefined): Place | null {
  if (typeof s !== "string") return null;
  const parts = s.split("~");
  if (parts.length === 3) parts.push("");  // 역코드 칸이 없는 손으로 쓴 링크
  if (parts.length < 4) return null;
  const [lat, lon, code] = parts.slice(-3), name = parts.slice(0, -3).join("~");
  const la = Number(lat), lo = Number(lon);
  if (!name || !Number.isFinite(la) || !Number.isFinite(lo)) return null;
  return { name, address: null, lat: la, lon: lo, kind: code ? "STATION" : "PLACE", stationCode: code || null };
}

/** 길(수집 중인 도시 쌍)의 하행 기준 출발·도착역을 장소로 */
export function corridorEnds(c: Corridor): [Place, Place] | null {
  const r = c.rail.DN;
  if (!r?.dep.lat || !r.arr.lat) return null;
  const st = (p: typeof r.dep): Place => ({ name: `${p.name}역`, address: null, lat: p.lat!, lon: p.lon!, kind: "STATION", stationCode: p.code });
  return [st(r.dep), st(r.arr)];
}

/** 입력이 비었을 때 보여 줄 추천: 길의 역들 (중복 제거) */
export function suggestedPlaces(cs: Corridor[] | null): Place[] {
  const m = new Map<string, Place>();
  for (const c of cs ?? []) for (const p of corridorEnds(c) ?? []) m.set(p.stationCode ?? p.name, p);
  return [...m.values()];
}

export const KIND_LABEL: Record<string, string> = { REGION: "지역", STATION: "기차역", PLACE: "장소", ADDRESS: "주소", CORRIDOR: "길" };
