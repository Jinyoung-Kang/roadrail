import { useEffect, useRef, useState } from "react";

/* 카카오 지도 JS SDK — 브라우저에 노출되는 유일한 키(NEXT_PUBLIC_KAKAO_JS_KEY). 실패하면 SVG 노선도로 대체. */
declare global { interface Window { kakao: any } }
const KEY = process.env.NEXT_PUBLIC_KAKAO_JS_KEY;
let loader: Promise<any> | null = null;

export const ROAD = "#2a78d6", RAIL = "#eb6834";

export interface MapLine { path: [number, number][]; color: string; dashed?: boolean; weight?: number }
/** warn = 돌발 안내 (삼각형) */
export interface MapMarker { lat: number; lon: number; label?: string; color: string; ring?: boolean; size?: number; warn?: boolean }
export interface MapLayers { lines: MapLine[]; markers: MapMarker[] }

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

/** 선(경로)과 점(출발·도착·역)을 그리는 지도. 히어로 배경(interactive=false)과 탐색용 지도 공용. */
export default function RouteMap({ layers, interactive = false, className = "", padBottom = 0, label = "노선 지도", focus = null }: {
  layers: MapLayers | null; interactive?: boolean; className?: string; padBottom?: number; label?: string;
  /** 바뀔 때마다(n 증가) 그 지점으로 확대·이동 — 예: 돌발 안내 '지도에서 보기' */
  focus?: { lat: number; lon: number; n: number } | null;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const mapRef = useRef<any>(null);
  const [failed, setFailed] = useState<string | null>(null);
  // 탐색용 지도는 기본으로 잠가 둔다 — 페이지를 스크롤할 때 휠이 지도 배율을 바꾸지 않게 (+/− 버튼은 항상 동작)
  const [unlocked, setUnlocked] = useState(false);
  const lock = (on: boolean) => {
    setUnlocked(!on);
    const m = mapRef.current;
    if (m) { m.setDraggable(!on); m.setZoomable(!on); }
  };
  const sig = layers ? JSON.stringify(layers).length + ":" + layers.lines.length + ":" + layers.markers.map((m) => m.lat.toFixed(3)).join() : "";

  useEffect(() => {
    if (!layers || !ref.current) return;
    let cancelled = false;
    let cleanup = () => {};
    loadKakao().then((kakao) => {
      if (cancelled || !ref.current) return;
      ref.current.innerHTML = "";
      const first = layers.markers[0] ?? { lat: 36.5, lon: 127.8 };
      const map = new kakao.maps.Map(ref.current, {
        center: new kakao.maps.LatLng(first.lat, first.lon), level: 10,
        draggable: false, scrollwheel: false, disableDoubleClickZoom: true,
      });
      map.setZoomable(false);
      mapRef.current = map;
      setUnlocked(false);
      if (interactive) map.addControl(new kakao.maps.ZoomControl(), kakao.maps.ControlPosition.RIGHT);
      const bounds = new kakao.maps.LatLngBounds();
      for (const l of layers.lines) {
        if (l.path.length < 2) continue;
        const path = l.path.map(([la, lo]) => { const p = new kakao.maps.LatLng(la, lo); bounds.extend(p); return p; });
        new kakao.maps.Polyline({ map, path, strokeWeight: l.weight ?? 5, strokeColor: l.color, strokeOpacity: 0.92,
          strokeStyle: l.dashed ? "shortdash" : "solid" });
      }
      for (const m of layers.markers) {
        const p = new kakao.maps.LatLng(m.lat, m.lon);
        bounds.extend(p);
        const size = m.size ?? 10;
        const dot = m.warn
          ? `<span style="display:inline-block;width:0;height:0;border-left:7px solid transparent;border-right:7px solid transparent;border-bottom:12px solid ${m.color};filter:drop-shadow(0 0 1px #fff)"></span>`
          : `<span style="display:inline-block;width:${size}px;height:${size}px;border-radius:50%;background:${m.ring ? "#fff" : m.color};border:2px solid ${m.ring ? m.color : "#fff"};box-shadow:0 0 0 1px rgba(0,0,0,.08)"></span>`;
        new kakao.maps.CustomOverlay({ map, position: p, yAnchor: m.label ? 1.25 : 0.5,
          content: m.label
            ? `<div style="font:500 12px Pretendard,system-ui;padding:4px 8px;border-radius:4px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.18);color:#171a20;white-space:nowrap;display:flex;align-items:center;gap:6px">${dot}${m.label}</div>`
            : dot });
      }
      // 컨테이너 크기가 확정된 다음에 맞춘다 (생성 직후 0 크기일 수 있음 · rAF 는 백그라운드 탭에서 멈추므로 타이머)
      const fit = () => { map.relayout(); map.setBounds(bounds, 60, 60, 60 + padBottom, 60); };
      setTimeout(fit, 30);
      window.addEventListener("resize", fit);
      cleanup = () => window.removeEventListener("resize", fit);
      setFailed(null);
    }).catch((e) => !cancelled && setFailed(e.message));
    return () => { cancelled = true; cleanup(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig, interactive, padBottom]);

  useEffect(() => {
    const m = mapRef.current, k = typeof window !== "undefined" ? window.kakao : null;
    if (!focus || !m || !k?.maps) return;
    m.setLevel(7);
    m.panTo(new k.maps.LatLng(focus.lat, focus.lon));
  }, [focus?.n]);  // eslint-disable-line react-hooks/exhaustive-deps

  if (failed || !KEY) return <SvgRoute layers={layers} className={className} note={failed ?? "카카오 JS 키 없음"} />;
  // 카카오 SDK 가 컨테이너를 position: relative 로 바꾸므로 배치는 바깥 래퍼가, 쌓임 맥락은 isolate 가 맡는다
  return (
    <div className={`${className} isolate ${interactive ? "" : "rr-map-muted"}`} aria-label={label}
         onMouseLeave={() => interactive && unlocked && lock(true)}>
      <div ref={ref} className="h-full w-full" />
      {interactive && (
        <button onClick={() => lock(unlocked)}
                className="absolute bottom-4 right-4 z-10 rounded bg-white/95 px-3 py-2 text-xs font-medium text-ink2 shadow-tile hover:bg-white">
          {unlocked ? "지도 조작 끄기" : "지도 조작하기 (이동 · 확대)"}
        </button>
      )}
    </div>
  );
}

/** 카카오 지도를 못 쓸 때의 SVG 대체 */
export function SvgRoute({ layers, className, note }: { layers: MapLayers | null; className?: string; note?: string }) {
  if (!layers) return <div className={className} />;
  const pts = [...layers.lines.flatMap((l) => l.path), ...layers.markers.map((m) => [m.lat, m.lon] as [number, number])];
  if (!pts.length) return <div className={className} />;
  const ys = pts.map((p) => p[0]), xs = pts.map((p) => p[1]);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const W = 1000, H = 600, pad = 90;
  const s = Math.min((W - 2 * pad) / Math.max(x1 - x0, 0.01), (H - 2 * pad) / Math.max(y1 - y0, 0.01));
  const px = (lon: number) => pad + (lon - x0) * s + ((W - 2 * pad) - (x1 - x0) * s) / 2;
  const py = (lat: number) => H - pad - (lat - y0) * s - ((H - 2 * pad) - (y1 - y0) * s) / 2;
  return (
    <div className={`${className} bg-gradient-to-b from-[#e9eef3] to-[#f7f8f9]`} title={note}>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-full w-full" preserveAspectRatio="xMidYMid meet" aria-label="노선도">
        {layers.lines.map((l, i) => (
          <polyline key={i} points={l.path.map(([la, lo]) => `${px(lo)},${py(la)}`).join(" ")} fill="none" stroke={l.color}
                    strokeWidth={l.weight ?? 4} strokeDasharray={l.dashed ? "10 8" : undefined} strokeLinejoin="round" strokeLinecap="round" />
        ))}
        {layers.markers.map((m, i) => (
          <g key={i}>
            <circle cx={px(m.lon)} cy={py(m.lat)} r={(m.size ?? 10) / 2 + 1} fill={m.ring ? "#fff" : m.color} stroke={m.ring ? m.color : "#fff"} strokeWidth={2} />
            {m.label && <text x={px(m.lon) + 12} y={py(m.lat) + 4} fontSize={16} fill="#393c41">{m.label}</text>}
          </g>
        ))}
      </svg>
    </div>
  );
}
