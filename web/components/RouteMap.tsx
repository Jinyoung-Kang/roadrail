import { useEffect, useMemo, useRef, useState } from "react";
import { frameSignature, layersSignature, type MapLayers, type MapMarker } from "@/lib/map";
import { sdkLoader } from "@/lib/sdkLoader";

/* 카카오 지도 JS SDK — 브라우저에 노출되는 유일한 키(NEXT_PUBLIC_KAKAO_JS_KEY). 실패하면 SVG 노선도로 대체. */
declare global { interface Window { kakao: any } }
const KEY = process.env.NEXT_PUBLIC_KAKAO_JS_KEY;

export const ROAD = "#2a78d6", RAIL = "#eb6834";

export type { MapLayers, MapLine, MapMarker } from "@/lib/map";

/**
 * 지도 표식 — HTML 문자열이 아니라 DOM 노드로 만든다.
 * 라벨(장소 이름 · 역 이름 · 돌발 유형)은 URL · API 에코 · 외부 데이터에서 오므로 textContent 로만 넣고,
 * 스타일도 속성별 대입(CSSOM)이라 값에 선언을 덧붙여 끼워 넣을 수 없다.
 */
function markerNode(m: MapMarker): HTMLElement {
  const dot = document.createElement("span");
  dot.style.display = "inline-block";
  if (m.warn) {
    Object.assign(dot.style, { width: "0", height: "0", borderLeft: "7px solid transparent", borderRight: "7px solid transparent",
      borderBottom: `12px solid ${m.color}`, filter: "drop-shadow(0 0 1px #fff)" });
  } else {
    const size = `${m.size ?? 10}px`;
    Object.assign(dot.style, { width: size, height: size, borderRadius: "50%", background: m.ring ? "#fff" : m.color,
      border: `2px solid ${m.ring ? m.color : "#fff"}`, boxShadow: "0 0 0 1px rgba(0,0,0,.08)" });
  }
  if (!m.label) return dot;
  const box = document.createElement("div");
  Object.assign(box.style, { font: "500 12px Pretendard,system-ui", padding: "4px 8px", borderRadius: "4px", background: "#fff",
    boxShadow: "0 1px 4px rgba(0,0,0,.18)", color: "#171a20", whiteSpace: "nowrap", display: "flex", alignItems: "center", gap: "6px" });
  box.append(dot, document.createTextNode(m.label));
  return box;
}

const loadSdk = sdkLoader({
  sdk: () => window.kakao,
  appendScript: (src) => {
    const s = document.createElement("script");
    s.src = src;
    s.async = true;
    document.head.appendChild(s);
    return s;
  },
  setTimeout: (fn, ms) => window.setTimeout(fn, ms),
}, `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${KEY}&autoload=false`);

function loadKakao(): Promise<any> {
  if (typeof window === "undefined") return Promise.reject(new Error("ssr"));
  if (!KEY) return Promise.reject(new Error("NEXT_PUBLIC_KAKAO_JS_KEY 없음"));
  return loadSdk();
}

/**
 * 선(경로)과 점(출발·도착·역)을 그리는 지도. 히어로 배경(interactive=false)과 탐색용 지도 공용.
 * 지도는 한 번만 만들고, 내용이 바뀌면 선 · 표식만 갈아끼운다. 범위 맞춤은 끝점 · 경로가 바뀔 때만 (WEB-03).
 */
export default function RouteMap({ layers, interactive = false, className = "", padBottom = 0, label = "노선 지도", focus = null }: {
  layers: MapLayers | null; interactive?: boolean; className?: string; padBottom?: number; label?: string;
  /** 바뀔 때마다(n 증가) 그 지점으로 확대·이동 — 예: 돌발 안내 '지도에서 보기' */
  focus?: { lat: number; lon: number; n: number } | null;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const mapRef = useRef<any>(null);
  const overlays = useRef<any[]>([]);
  const bounds = useRef<any>(null);
  const fitted = useRef("");
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  // 탐색용 지도는 기본으로 잠가 둔다 — 페이지를 스크롤할 때 휠이 지도 배율을 바꾸지 않게 (+/− 버튼은 항상 동작)
  const [unlocked, setUnlocked] = useState(false);
  const lock = (on: boolean) => {
    setUnlocked(!on);
    const m = mapRef.current;
    if (m) { m.setDraggable(!on); m.setZoomable(!on); }
  };
  // 경로 좌표가 수천 개라 서명은 layers 가 바뀔 때만 계산 (페이지는 layers 를 useMemo 로 넘긴다)
  const sig = useMemo(() => layersSignature(layers), [layers]);
  const frame = useMemo(() => frameSignature(layers), [layers]);
  const hasLayers = layers !== null;
  // 컨테이너 크기가 확정된 다음에 맞춘다 (생성 직후 0 크기일 수 있음 · rAF 는 백그라운드 탭에서 멈추므로 타이머)
  const fit = () => {
    const m = mapRef.current, b = bounds.current;
    if (!m || !b || b.isEmpty()) return;
    m.relayout();
    m.setBounds(b, 60, 60, 60 + padBottom, 60);
  };

  // 지도는 한 번만 만든다 — 그릴 내용이 처음 생길 때
  useEffect(() => {
    if (!hasLayers || !ref.current || mapRef.current) return;
    let cancelled = false;
    loadKakao().then((kakao) => {
      if (cancelled || !ref.current) return;
      ref.current.innerHTML = "";
      const map = new kakao.maps.Map(ref.current, {
        center: new kakao.maps.LatLng(36.5, 127.8), level: 10,
        draggable: false, scrollwheel: false, disableDoubleClickZoom: true,
      });
      map.setZoomable(false);
      if (interactive) map.addControl(new kakao.maps.ZoomControl(), kakao.maps.ControlPosition.RIGHT);
      mapRef.current = map;
      setUnlocked(false);
      setFailed(null);
      setReady(true);
    }).catch((e) => !cancelled && setFailed(e.message));
    return () => { cancelled = true; };
  }, [hasLayers, interactive]);

  // 내용이 바뀌면 선 · 표식만 갈아끼운다
  useEffect(() => {
    const map = mapRef.current, kakao = typeof window !== "undefined" ? window.kakao : null;
    if (!ready || !map || !kakao?.maps || !layers) return;
    overlays.current.forEach((o) => o.setMap(null));
    overlays.current = [];
    const b = new kakao.maps.LatLngBounds();
    for (const l of layers.lines) {
      if (l.path.length < 2) continue;
      const path = l.path.map(([la, lo]) => { const p = new kakao.maps.LatLng(la, lo); b.extend(p); return p; });
      overlays.current.push(new kakao.maps.Polyline({ map, path, strokeWeight: l.weight ?? 5, strokeColor: l.color, strokeOpacity: 0.92,
        strokeStyle: l.dashed ? "shortdash" : "solid" }));
    }
    for (const m of layers.markers) {
      const p = new kakao.maps.LatLng(m.lat, m.lon);
      b.extend(p);
      overlays.current.push(new kakao.maps.CustomOverlay({ map, position: p, yAnchor: m.label ? 1.25 : 0.5, content: markerNode(m) }));
    }
    bounds.current = b;
    if (frame !== fitted.current) {
      // 맞춘 범위는 실제로 맞춘 뒤에 기록 — 30ms 안에 내용이 또 바뀌어 타이머가 취소돼도 다음 실행이 다시 맞춘다
      const id = setTimeout(() => { fitted.current = frame; fit(); }, 30);
      return () => clearTimeout(id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, sig]);

  useEffect(() => {
    if (!ready) return;
    window.addEventListener("resize", fit);
    return () => window.removeEventListener("resize", fit);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, padBottom]);

  useEffect(() => {
    const m = mapRef.current, k = typeof window !== "undefined" ? window.kakao : null;
    if (!focus || !m || !k?.maps) return;
    m.setLevel(7);
    m.panTo(new k.maps.LatLng(focus.lat, focus.lon));
  }, [focus?.n]);  // eslint-disable-line react-hooks/exhaustive-deps

  if (failed || !KEY) return <SvgRoute layers={layers} className={className} note={failed ?? "카카오 JS 키 없음"} />;
  // 카카오 SDK 가 컨테이너를 position: relative 로 바꾸므로 배치는 바깥 래퍼가, 쌓임 맥락은 isolate 가 맡는다
  return (
    <div className={`${className} isolate ${interactive ? "" : "rr-map-muted"}`} role="region" aria-label={label}
         onMouseLeave={() => interactive && unlocked && lock(true)}>
      <div ref={ref} className="h-full w-full" />
      {interactive && (
        <button onClick={() => lock(unlocked)}
                className="absolute bottom-4 right-4 z-10 rounded-sm bg-white/95 px-3 py-2 text-xs font-medium text-ink2 shadow-tile hover:bg-white">
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
    <div className={`${className} bg-linear-to-b from-[#e9eef3] to-[#f7f8f9]`} title={note}>
      <svg viewBox={`0 0 ${W} ${H}`} className="h-full w-full" preserveAspectRatio="xMidYMid meet" role="img" aria-label="노선도">
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
