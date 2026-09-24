import { RAIL, ROAD, type MapLayers } from "@/components/RouteMap";
import type { Corridor, Dir, Trip } from "./types";

/** 길(수집 중인 도시 쌍): 영업소 체인(파랑) + 역 쌍(주황 점선) */
export function corridorLayers(c: Corridor | null | undefined, dir: Dir, withUnits = false): MapLayers | null {
  if (!c) return null;
  const units = c.road[dir]?.units.filter((u) => u.lat && u.lon) ?? [];
  const rail = c.rail[dir];
  const lines: MapLayers["lines"] = [{ path: units.map((u) => [u.lat!, u.lon!]), color: ROAD }];
  const markers: MapLayers["markers"] = [];
  if (rail?.dep.lat && rail.arr.lat) {
    lines.push({ path: [[rail.dep.lat, rail.dep.lon!], [rail.arr.lat, rail.arr.lon!]], color: RAIL, dashed: true, weight: 3 });
    markers.push({ lat: rail.dep.lat, lon: rail.dep.lon!, label: `${rail.dep.name}역`, color: RAIL },
                 { lat: rail.arr.lat, lon: rail.arr.lon!, label: `${rail.arr.name}역`, color: RAIL });
  }
  if (withUnits) units.forEach((u) => markers.push({ lat: u.lat!, lon: u.lon!, color: ROAD, ring: true, size: 8 }));
  return { lines, markers };
}

/** 어디서 → 어디로: 카카오 자동차 경로(파랑) + 기차 여정(주황 점선, 환승역 표시) + 출발·도착 지점 */
export function tripLayers(t: Trip | null, from?: { lat: number; lon: number; name: string } | null,
                           to?: { lat: number; lon: number; name: string } | null): MapLayers | null {
  const a = t?.from ?? from, b = t?.to ?? to;
  if (!a || !b) return null;
  const lines: MapLayers["lines"] = [];
  const markers: MapLayers["markers"] = [
    { lat: a.lat, lon: a.lon, label: a.name, color: "#171a20" },
    { lat: b.lat, lon: b.lon, label: b.name, color: "#171a20" },
  ];
  if (t?.car.path?.length) lines.push({ path: t.car.path, color: ROAD });
  const j = t?.rail?.journeys?.[0];
  if (j) {
    const pts: [number, number][] = [];
    j.legs.forEach((l, i) => {
      if (i === 0 && l.fromLat != null && l.fromLon != null) pts.push([l.fromLat, l.fromLon]);
      if (l.toLat != null && l.toLon != null) pts.push([l.toLat, l.toLon]);
    });
    if (pts.length >= 2) lines.push({ path: pts, color: RAIL, dashed: true, weight: 3 });
    j.legs.forEach((l, i) => {
      if (i === 0 && l.fromLat != null && j.access.straightKm > 0.3) markers.push({ lat: l.fromLat, lon: l.fromLon!, label: `${l.fromName}역`, color: RAIL });
      if (l.toLat != null && (i < j.legs.length - 1 || j.egress.straightKm > 0.3)) {
        markers.push({ lat: l.toLat, lon: l.toLon!, label: i < j.legs.length - 1 ? `${l.toName} 환승` : `${l.toName}역`, color: RAIL });
      }
    });
  }
  return { lines, markers };
}
