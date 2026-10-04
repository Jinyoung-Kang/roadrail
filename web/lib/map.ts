/** 지도에 그릴 선 · 점 — 화면 컴포넌트(RouteMap)와 그릴 내용을 만드는 순수 함수(layers · traffic)가 함께 쓰는 타입 */
export interface MapLine { path: [number, number][]; color: string; dashed?: boolean; weight?: number }
/** warn = 돌발 안내 (삼각형) */
export interface MapMarker { lat: number; lon: number; label?: string; color: string; ring?: boolean; size?: number; warn?: boolean }
export interface MapLayers { lines: MapLine[]; markers: MapMarker[] }

/** 다시 그릴지 판단하는 서명 — 그리는 내용 전부(좌표 · 색 · 모양 · 라벨). 색만 바뀌어도 다시 그린다 (WEB-03) */
export const layersSignature = (layers: MapLayers | null): string => (layers ? JSON.stringify(layers) : "");

/**
 * 지도를 맞출 범위의 서명 — 선과 일반 표식의 경계 상자(소수 셋째 자리 ≈ 100m).
 * 돌발 표식이 늘거나 소통 색이 바뀌어도 그대로라, 사용자가 옮긴 위치 · 배율을 지킨다. 끝점 · 경로가 바뀌면 다시 맞춘다.
 */
export function frameSignature(layers: MapLayers | null): string {
  if (!layers) return "";
  let s = Infinity, w = Infinity, n = -Infinity, e = -Infinity;
  const add = (lat: number, lon: number) => { s = Math.min(s, lat); n = Math.max(n, lat); w = Math.min(w, lon); e = Math.max(e, lon); };
  for (const l of layers.lines) for (const [lat, lon] of l.path) add(lat, lon);
  for (const m of layers.markers) if (!m.warn) add(m.lat, m.lon);
  return Number.isFinite(s) ? [s, w, n, e].map((v) => v.toFixed(3)).join(",") : "";
}
