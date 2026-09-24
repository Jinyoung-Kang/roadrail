import { useMemo, useState } from "react";
import {
  Bar, BarChart, CartesianGrid, ComposedChart, Legend, Line, ReferenceArea, ResponsiveContainer, Scatter, Tooltip, XAxis, YAxis,
} from "recharts";
import { DASH, DOW, dur, hm, mdhm } from "@/lib/format";
import type { Baseline, Series } from "@/lib/types";

/* 색은 dataviz 기본 팔레트 (검증: scripts/validate_palette.js — CVD ΔE 9.2 · 정상시 27.6) */
export const C = { road: "#2a78d6", rail: "#eb6834", aqua: "#1baf7a", base: "#898781", grid: "#e1e0d9", rain: "#cde2fb" };
const SEQ = ["#cde2fb", "#b7d3f6", "#9ec5f4", "#86b6ef", "#6da7ec", "#5598e7", "#3987e5", "#2a78d6", "#256abf", "#1c5cab", "#184f95", "#104281", "#0d366b"];

function Box({ children }: { children: React.ReactNode }) {
  return <div className="rounded bg-white px-3 py-2 text-xs shadow-lg ring-1 ring-black/10">{children}</div>;
}

/** 통행시간 추이 — 관측(파랑) · 기준선 p50(회색 점선) · 카카오 ETA(청록 점) · 강수 예보(옅은 음영) */
export function TravelChart({ series }: { series: Series }) {
  const data = useMemo(() => {
    const m = new Map<number, any>();
    for (const p of series.points) m.set(new Date(p.t).getTime(), { t: new Date(p.t).getTime(), obs: p.travelSec / 60, base: p.baselineP50Sec ? p.baselineP50Sec / 60 : null, q: p.quality });
    for (const k of series.kakaoEta) {
      const t = new Date(k.departAt).getTime();
      m.set(t, { ...(m.get(t) ?? { t }), kakao: k.durationSec / 60 });
    }
    return [...m.values()].sort((a, b) => a.t - b.t);
  }, [series]);
  const rainBands = useMemo(() => series.rain.filter((r) => (r.pop ?? 0) >= 60 || (r.pty && r.pty !== "없음"))
    .map((r) => ({ x1: new Date(r.t).getTime(), x2: new Date(r.t).getTime() + 3600_000 })), [series]);
  const multiDay = data.length > 0 && data[data.length - 1].t - data[0].t > 36 * 3600_000;
  return (
    <div className="h-[380px]">
      <ResponsiveContainer>
        <ComposedChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} />
          {rainBands.map((b, i) => <ReferenceArea key={i} x1={b.x1} x2={b.x2} fill={C.rain} fillOpacity={0.6} ifOverflow="hidden" />)}
          <XAxis dataKey="t" type="number" scale="time" domain={["dataMin", "dataMax"]} tickLine={false} axisLine={{ stroke: "#c3c2b7" }}
                 tickFormatter={(t) => (multiDay ? mdhm(new Date(t).toISOString()) : hm(new Date(t).toISOString()))} minTickGap={48} />
          <YAxis tickLine={false} axisLine={false} width={48} tickFormatter={(v) => `${Math.round(v)}분`} />
          <Tooltip content={({ active, payload }) => {
            if (!active || !payload?.length) return null;
            const d = payload[0].payload;
            return (
              <Box>
                <div className="font-medium text-ink">{mdhm(new Date(d.t).toISOString())}</div>
                {d.obs != null && <div className="mt-1 flex items-center gap-2"><i className="h-2 w-2 rounded-full" style={{ background: C.road }} />관측 {dur(d.obs * 60)}{d.q === "FILLED" && " (일부 보정)"}</div>}
                {d.base != null && <div className="flex items-center gap-2"><i className="h-2 w-2 rounded-full" style={{ background: C.base }} />기준선 {dur(d.base * 60)}</div>}
                {d.kakao != null && <div className="flex items-center gap-2"><i className="h-2 w-2 rounded-full" style={{ background: C.aqua }} />카카오 {dur(d.kakao * 60)}</div>}
              </Box>
            );
          }} />
          <Legend verticalAlign="top" align="right" height={28} iconType="plainline" wrapperStyle={{ fontSize: 12 }} />
          <Line name="관측 통행시간" dataKey="obs" stroke={C.road} strokeWidth={2} dot={false} connectNulls={false} isAnimationActive={false} />
          <Line name="같은 요일·시간 p50" dataKey="base" stroke={C.base} strokeWidth={2} strokeDasharray="4 4" dot={false} isAnimationActive={false} />
          <Scatter name="카카오 미래 운행 정보" dataKey="kakao" fill={C.aqua} shape="circle" isAnimationActive={false} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

/** 요일 × 시간 히트맵 (기준선 p50 의 시간 평균). 결측은 빈칸 (FR-601) */
export function Heatmap({ baseline }: { baseline: Baseline }) {
  const [hover, setHover] = useState<{ dow: number; h: number; v: number; n: number } | null>(null);
  const grid = useMemo(() => {
    const acc = new Map<string, { s: number; c: number; n: number }>();
    for (const c of baseline.cells) {
      const k = `${c.dow}-${Math.floor(c.slotIdx / 12)}`;
      const a = acc.get(k) ?? { s: 0, c: 0, n: 0 };
      a.s += c.p50Sec; a.c += 1; a.n = Math.max(a.n, c.n);
      acc.set(k, a);
    }
    const vals = [...acc.values()].map((a) => a.s / a.c);
    return { acc, min: Math.min(...vals), max: Math.max(...vals) };
  }, [baseline]);
  const color = (v: number) => {
    const t = grid.max > grid.min ? (v - grid.min) / (grid.max - grid.min) : 0.5;
    return SEQ[Math.min(SEQ.length - 1, Math.floor(t * SEQ.length))];
  };
  const rows = [1, 2, 3, 4, 5, 6, 7, 0];
  return (
    <div>
      <div className="overflow-x-auto">
        <div className="min-w-[640px]">
          <div className="grid grid-cols-[48px_repeat(24,minmax(0,1fr))] gap-[2px]">
            <div />
            {Array.from({ length: 24 }, (_, h) => <div key={h} className="text-center text-[10px] text-faint">{h % 3 === 0 ? h : ""}</div>)}
            {rows.map((dow) => (
              <Row key={dow} dow={dow} grid={grid.acc} color={color} onHover={setHover} />
            ))}
          </div>
        </div>
      </div>
      <div className="mt-4 flex flex-wrap items-center justify-between gap-4 text-xs text-muted">
        <div className="flex items-center gap-2">
          <span>{dur(grid.min)}</span>
          <div className="flex h-2 w-40 overflow-hidden rounded-sm">{SEQ.map((c) => <span key={c} className="flex-1" style={{ background: c }} />)}</div>
          <span>{dur(grid.max)}</span>
          <span className="ml-2 inline-block h-3 w-3 rounded-sm bg-cloud ring-1 ring-line" /> 데이터 없음
        </div>
        <div className="min-h-[18px] tabular text-ink2">
          {hover ? `${hover.dow === 0 ? "전체 요일" : DOW[hover.dow] + "요일"} ${hover.h}시 · p50 ${dur(hover.v)} · 표본 최대 ${hover.n}일` : "칸에 마우스를 올리면 값이 보입니다"}
        </div>
      </div>
    </div>
  );
}

function Row({ dow, grid, color, onHover }: { dow: number; grid: Map<string, { s: number; c: number; n: number }>;
  color: (v: number) => string; onHover: (h: any) => void }) {
  return (
    <>
      <div className="flex items-center text-xs text-muted">{dow === 0 ? "전체" : DOW[dow]}</div>
      {Array.from({ length: 24 }, (_, h) => {
        const a = grid.get(`${dow}-${h}`);
        const v = a ? a.s / a.c : null;
        return (
          <div key={h} className={`h-7 rounded-[3px] ${v === null ? "bg-cloud" : "cursor-crosshair hover:ring-2 hover:ring-ink"}`}
               style={v === null ? undefined : { background: color(v) }}
               title={v === null ? "데이터 없음" : `${dur(v)}`}
               onMouseEnter={() => v !== null && onHover({ dow, h, v, n: a!.n })} onMouseLeave={() => onHover(null)} />
        );
      })}
    </>
  );
}

/** 단일 계열 막대 (범례 없음 — 제목이 계열 이름) */
export function SimpleBars({ data, x, y, color, format, height = 240, yFormat }: {
  data: any[]; x: string; y: string; color: string; format: (v: any, d: any) => string; height?: number; yFormat?: (v: number) => string;
}) {
  return (
    <div style={{ height }}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} barCategoryGap={4}>
          <CartesianGrid vertical={false} />
          <XAxis dataKey={x} tickLine={false} axisLine={{ stroke: "#c3c2b7" }} interval={0} />
          <YAxis tickLine={false} axisLine={false} width={44} tickFormatter={yFormat} />
          <Tooltip cursor={{ fill: "rgba(0,0,0,0.04)" }} content={({ active, payload }) =>
            active && payload?.length ? <Box><div className="font-medium">{String(payload[0].payload[x])}</div><div>{format(payload[0].value, payload[0].payload)}</div></Box> : null} />
          <Bar dataKey={y} fill={color} radius={[4, 4, 0, 0]} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/** 백테스트 MAE — horizon 별 모델 3개 (범례 + 표로 식별, 색만으로 구분하지 않음) */
export function MaeChart({ cells }: { cells: { horizonMin: number; model: string; maeSec: number | null; n: number }[] }) {
  const data = useMemo(() => {
    const m = new Map<number, any>();
    for (const c of cells) {
      const r = m.get(c.horizonMin) ?? { h: `${c.horizonMin}분` };
      r[c.model] = c.maeSec === null ? null : c.maeSec / 60;
      r[`${c.model}_n`] = c.n;
      m.set(c.horizonMin, r);
    }
    return [...m.entries()].sort((a, b) => a[0] - b[0]).map((e) => e[1]);
  }, [cells]);
  if (!data.length) return null;
  const names: Record<string, string> = { M0: "M0 기준선", M1: "M1 기준선+편차", persistence: "지속(현재값)" };
  return (
    <div className="h-[320px]">
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} barGap={2} barCategoryGap="24%">
          <CartesianGrid vertical={false} />
          <XAxis dataKey="h" tickLine={false} axisLine={{ stroke: "#c3c2b7" }} />
          <YAxis tickLine={false} axisLine={false} width={48} tickFormatter={(v) => `${Math.round(v)}분`} />
          <Tooltip cursor={{ fill: "rgba(0,0,0,0.04)" }} content={({ active, payload }) => active && payload?.length ? (
            <Box>
              <div className="font-medium">{payload[0].payload.h} 뒤</div>
              {payload.map((p) => <div key={String(p.dataKey)} className="flex items-center gap-2"><i className="h-2 w-2 rounded-full" style={{ background: p.color }} />{names[String(p.dataKey)]}: {p.value == null ? DASH : `${(p.value as number).toFixed(1)}분`} <span className="text-faint">n={p.payload[`${String(p.dataKey)}_n`]}</span></div>)}
            </Box>) : null} />
          <Legend verticalAlign="top" align="right" height={28} wrapperStyle={{ fontSize: 12 }} formatter={(v) => names[v] ?? v} />
          <Bar dataKey="M0" fill={C.road} radius={[4, 4, 0, 0]} isAnimationActive={false} />
          <Bar dataKey="M1" fill={C.rail} radius={[4, 4, 0, 0]} isAnimationActive={false} />
          <Bar dataKey="persistence" fill={C.aqua} radius={[4, 4, 0, 0]} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
