import { useEffect, useRef, useState } from "react";
import type { Corridor, Dir } from "@/lib/types";

/* 카카오 지도 JS SDK — 브라우저에 노출되는 유일한 키(NEXT_PUBLIC_KAKAO_JS_KEY). 실패하면 SVG 노선도로 대체. */
declare global { interface Window { kakao: any } }
const KEY = process.env.NEXT_PUBLIC_KAKAO_JS_KEY;
let loader: Promise<any> | null = null;

function loadKakao(): Promise<any> {
  if (typeof window === "undefined") return Promise.reject(new Error("ssr"));
  if (!KEY) return Promise.reject(new Error("NEXT_PUBLIC_KAKAO_JS_KEY 없음"));
  if (window.kakao?.maps?.LatLng) return Promise.resolve(window.kakao);
  if (!loader) {
    loader = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KEY}&autoload=false`;
      s.async = true;
      s.onload = () => (window.kakao?.maps ? window.kakao.maps.load(() => resolve(window.kakao)) : reject(new Error("kakao 로드 실패")));
      s.onerror = () => reject(new Error("kakao 스크립트 로드 실패 (도메인 등록 확인)"));
      document.head.appendChild(s);
      setTimeout(() => reject(new Error("kakao 로드 시간 초과")), 8000);
    }).catch((e) => { loader = null; throw e; });
  }
  return loader;
}

const ROAD = "#2a78d6", RAIL = "#eb6834";

export default function RouteMap({ corridor, dir, interactive = false, className = "", padBottom = 0 }: {
  corridor: Corridor | null; dir: Dir; interactive?: boolean; className?: string; padBottom?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    if (!corridor || !ref.current) return;
    let cancelled = false;
    let cleanup = () => {};
    loadKakao().then((kakao) => {
      if (cancelled || !ref.current) return;
      ref.current.innerHTML = "";
      const units = corridor.road[dir]?.units.filter((u) => u.lat && u.lon) ?? [];
      const rail = corridor.rail[dir];
      const map = new kakao.maps.Map(ref.current, {
        center: new kakao.maps.LatLng(units[0]?.lat ?? 36.5, units[0]?.lon ?? 127.5), level: 10,
        draggable: interactive, scrollwheel: interactive, disableDoubleClickZoom: !interactive,
      });
      if (!interactive) map.setZoomable(false);
      const bounds = new kakao.maps.LatLngBounds();
      const path = units.map((u) => { const p = new kakao.maps.LatLng(u.lat, u.lon); bounds.extend(p); return p; });
      new kakao.maps.Polyline({ map, path, strokeWeight: 5, strokeColor: ROAD, strokeOpacity: 0.9, strokeStyle: "solid" });
      if (rail?.dep.lat && rail?.arr.lat) {
        const a = new kakao.maps.LatLng(rail.dep.lat, rail.dep.lon), b = new kakao.maps.LatLng(rail.arr.lat, rail.arr.lon);
        bounds.extend(a); bounds.extend(b);
        new kakao.maps.Polyline({ map, path: [a, b], strokeWeight: 3, strokeColor: RAIL, strokeOpacity: 0.9, strokeStyle: "shortdash" });
        for (const [p, name] of [[a, rail.dep.name], [b, rail.arr.name]] as const) {
          new kakao.maps.CustomOverlay({ map, position: p, yAnchor: 1.3,
            content: `<div style="font:500 12px Pretendard,system-ui;padding:4px 8px;border-radius:4px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.18);color:#171a20;white-space:nowrap"><span style="color:${RAIL}">●</span> ${name}역</div>` });
        }
      }
      if (interactive) {
        units.forEach((u, i) => new kakao.maps.CustomOverlay({ map, position: new kakao.maps.LatLng(u.lat, u.lon), yAnchor: 0.5,
          content: `<div title="${u.name} (${u.code})" style="width:${i === 0 || i === units.length - 1 ? 12 : 8}px;height:${i === 0 || i === units.length - 1 ? 12 : 8}px;border-radius:50%;background:#fff;border:2px solid ${ROAD}"></div>` }));
      }
      // 컨테이너 크기가 확정된 다음 프레임에 맞춘다 (생성 직후에는 0 크기일 수 있음)
      const fit = () => { map.relayout(); map.setBounds(bounds, 60, 60, 60 + padBottom, 60); };
      setTimeout(fit, 30);  // rAF 는 백그라운드 탭에서 멈추므로 타이머로
      const onResize = () => fit();
      window.addEventListener("resize", onResize);
      cleanup = () => window.removeEventListener("resize", onResize);
      setFailed(null);
    }).catch((e) => !cancelled && setFailed(e.message));
    return () => { cancelled = true; cleanup(); };
  }, [corridor, dir, interactive, padBottom]);

  if (failed || !KEY) return <SvgRoute corridor={corridor} dir={dir} className={className} note={failed ?? "카카오 JS 키 없음"} />;
  // 카카오 SDK 가 컨테이너를 position: relative 로 바꾸므로 배치는 바깥 래퍼가 맡는다
  return (
    <div className={`${className} isolate ${interactive ? "" : "rr-map-muted"}`} aria-label={`${corridor?.name ?? ""} 노선 지도`}>
      <div ref={ref} className="h-full w-full" />
    </div>
  );
}

/** 카카오 지도를 못 쓸 때의 SVG 노선도 (도로 = 파랑 실선, 철도 = 주황 점선) */
export function SvgRoute({ corridor, dir, className, note }: { corridor: Corridor | null; dir: Dir; className?: string; note?: string }) {
  if (!corridor) return <div className={className} />;
  const units = corridor.road[dir]?.units.filter((u) => u.lat && u.lon) ?? [];
  const rail = corridor.rail[dir];
  const pts = [...units.map((u) => [u.lon!, u.lat!]), ...(rail?.dep.lat ? [[rail.dep.lon!, rail.dep.lat!], [rail.arr.lon!, rail.arr.lat!]] : [])];
  const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const W = 1000, H = 600, pad = 90;
  const s = Math.min((W - 2 * pad) / Math.max(x1 - x0, 0.01), (H - 2 * pad) / Math.max(y1 - y0, 0.01));
  const px = (lon: number) => pad + (lon - x0) * s + ((W - 2 * pad) - (x1 - x0) * s) / 2;
  const py = (lat: number) => H - pad - (lat - y0) * s - ((H - 2 * pad) - (y1 - y0) * s) / 2;
  return (
    <div className={`${className} bg-gradient-to-b from-[#e9eef3] to-[#f7f8f9]`} title={note}>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-full w-full" preserveAspectRatio="xMidYMid meet" aria-label={`${corridor.name} 노선도`}>
        <polyline points={units.map((u) => `${px(u.lon!)},${py(u.lat!)}`).join(" ")} fill="none" stroke={ROAD} strokeWidth={4}
                  strokeLinejoin="round" strokeLinecap="round" />
        {rail?.dep.lat && rail.arr.lat && (
          <line x1={px(rail.dep.lon!)} y1={py(rail.dep.lat)} x2={px(rail.arr.lon!)} y2={py(rail.arr.lat)} stroke={RAIL}
                strokeWidth={3} strokeDasharray="10 8" />
        )}
        {units.map((u, i) => (
          <circle key={u.code + i} cx={px(u.lon!)} cy={py(u.lat!)} r={i === 0 || i === units.length - 1 ? 7 : 4} fill="#fff" stroke={ROAD} strokeWidth={2} />
        ))}
        {rail?.dep.lat && [rail.dep, rail.arr].map((st) => (
          <g key={st.code}>
            <circle cx={px(st.lon!)} cy={py(st.lat!)} r={7} fill={RAIL} stroke="#fff" strokeWidth={2} />
            <text x={px(st.lon!) + 12} y={py(st.lat!) + 4} fontSize={16} fill="#393c41">{st.name}역</text>
          </g>
        ))}
      </svg>
    </div>
  );
}
