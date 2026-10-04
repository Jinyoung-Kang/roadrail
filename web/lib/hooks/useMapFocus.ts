import { useState } from "react";

/** 돌발 안내 '지도에서 보기' → 아래 지도로 내려가 그 지점으로 확대 (n 이 늘 때마다 다시) */
export function useMapFocus(mapId = "map") {
  const [focus, setFocus] = useState<{ lat: number; lon: number; n: number } | null>(null);
  const locate = (p: { lat: number | null; lon: number | null }) => {
    if (p.lat == null || p.lon == null) return;
    setFocus((f) => ({ lat: p.lat!, lon: p.lon!, n: (f?.n ?? 0) + 1 }));
    document.getElementById(mapId)?.scrollIntoView({ behavior: "smooth", block: "start" });
  };
  return { focus, locate };
}
