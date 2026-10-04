/** 지도에 그릴 선 · 점 — 화면 컴포넌트(RouteMap)와 그릴 내용을 만드는 순수 함수(layers · traffic)가 함께 쓰는 타입 */
export interface MapLine { path: [number, number][]; color: string; dashed?: boolean; weight?: number }
/** warn = 돌발 안내 (삼각형) */
export interface MapMarker { lat: number; lon: number; label?: string; color: string; ring?: boolean; size?: number; warn?: boolean }
export interface MapLayers { lines: MapLine[]; markers: MapMarker[] }

/** 다시 그릴지 판단하는 서명 */
export const layersSignature = (layers: MapLayers | null): string =>
  layers ? JSON.stringify(layers).length + ":" + layers.lines.length + ":" + layers.markers.map((m) => m.lat.toFixed(3)).join() : "";
