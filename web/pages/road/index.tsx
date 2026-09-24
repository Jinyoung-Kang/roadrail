import Link from "next/link";
import { useRouter } from "next/router";
import { useMemo } from "react";
import Layout from "@/components/Layout";
import RouteMap, { ROAD, type MapLayers } from "@/components/RouteMap";
import SearchPicker from "@/components/SearchPicker";
import { SimpleBars } from "@/components/Charts";
import { Empty, ErrorBox, Loading, Note, PageHero, Section, Segmented, Spec, SpecStrip } from "@/components/ui";
import { qs, useApi } from "@/lib/api";
import { DASH, dur, durMin, durParts, hm, num, pct } from "@/lib/format";
import { decodePlace, encodePlace, KIND_LABEL, suggestedPlaces } from "@/lib/places";
import type { Place, RouteAnalysis, RouteSummary } from "@/lib/types";
import { useCorridors } from "@/lib/useCorridors";

const DEPART = [0, 60, 120, 180].map((v) => ({ value: v, label: v === 0 ? "지금" : `+${v / 60}시간` }));
/* 도로 종류 — 범주형 팔레트 슬롯 순서 고정 (dataviz 기본: 파랑 · 청록 · 주황 · 회색) */
const TYPE_COLOR: Record<string, string> = { 고속도로: "#2a78d6", 도시고속도로: "#1baf7a", 국도: "#eb6834", 일반도로: "#898781" };
const TYPE_NOTE = "국도 구간이 도로명(예: 경수대로)으로 표기되면 일반도로로 집계됩니다.";
const TRAFFIC: Record<string, { cls: string; icon: string }> = {
  원활: { cls: "text-good", icon: "●" }, 서행: { cls: "text-warn", icon: "▲" }, 지체: { cls: "text-serious", icon: "▲" },
  정체: { cls: "text-crit", icon: "■" }, 사고: { cls: "text-crit", icon: "✕" }, "정보 없음": { cls: "text-faint", icon: "·" },
};

function Traffic({ s }: { s: string }) {
  const t = TRAFFIC[s] ?? TRAFFIC["정보 없음"];
  return <span className="inline-flex items-center gap-1"><span className={t.cls} aria-hidden>{t.icon}</span>{s}</span>;
}

function TypeBar({ r }: { r: RouteSummary }) {
  if (!r.byType.length) return null;
  return (
    <div>
      <div className="flex h-3 w-full gap-[2px] overflow-hidden rounded-sm" role="img"
           aria-label={r.byType.map((b) => `${b.type} ${pct(b.share)}`).join(", ")}>
        {r.byType.map((b) => <span key={b.type} style={{ width: `${b.share * 100}%`, background: TYPE_COLOR[b.type] }} title={`${b.type} ${pct(b.share)}`} />)}
      </div>
      <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-ink2">
        {r.byType.map((b) => (
          <li key={b.type} className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ background: TYPE_COLOR[b.type] }} aria-hidden />
            {b.type} <span className="tabular text-muted">{pct(b.share)} · {num(b.distanceM / 1000, 0)}km · {durMin(b.durationSec / 60)}</span>
          </li>
        ))}
      </ul>
      <p className="mt-1 text-[11px] text-faint">{TYPE_NOTE}</p>
    </div>
  );
}

export default function RoadIndex() {
  const router = useRouter();
  const q = router.query;
  const corridors = useCorridors();
  const suggestions = useMemo(() => suggestedPlaces(corridors.data), [corridors.data]);
  const from = decodePlace(q.from) ?? suggestions[0] ?? null;
  const to = decodePlace(q.to) ?? suggestions[1] ?? null;
  const departIn = Number(q.t ?? 0) || 0;
  const set = (p: Record<string, string | number>) => router.replace({ pathname: "/road", query: {
    ...(from ? { from: encodePlace(from) } : {}), ...(to ? { to: encodePlace(to) } : {}), t: departIn, ...p } },
    undefined, { shallow: true, scroll: false });
  const url = router.isReady && from && to ? `/api/v1/road/route?${qs({ fromLat: from.lat, fromLon: from.lon, fromName: from.name,
    toLat: to.lat, toLon: to.lon, toName: to.name, departIn })}` : null;
  const a = useApi<RouteAnalysis>(url);
  const d = a.data && from && a.data.from.name === from.name && a.data.to.name === to?.name ? a.data : null;
  const rec = d?.recommended, avo = d?.avoidMotorway;
  const motorway = rec?.byType.filter((b) => b.type === "고속도로" || b.type === "도시고속도로").reduce((s, b) => s + b.share, 0);
  const recP = durParts(rec?.durationSec != null ? rec.durationSec / 60 : null);
  const avoP = durParts(avo?.durationSec != null ? avo.durationSec / 60 : null);
  const layers: MapLayers | null = d ? {
    lines: [
      ...(avo?.path.length ? [{ path: avo.path, color: "#898781", dashed: true, weight: 4 }] : []),
      ...(rec?.path.length ? [{ path: rec.path, color: ROAD, weight: 5 }] : []),
    ],
    markers: [{ lat: d.from.lat, lon: d.from.lon, label: d.from.name, color: "#171a20" }, { lat: d.to.lat, lon: d.to.lon, label: d.to.name, color: "#171a20" }],
  } : null;
  const picker = (label: string, value: Place | null, key: "from" | "to") => (
    <SearchPicker<Place> label={label} placeholder="지역 · 역 · 장소 검색" value={value?.name ?? ""} className="w-full sm:w-[300px]"
      search={(term) => `/api/v1/places/search?q=${encodeURIComponent(term)}`} suggestions={suggestions}
      keyOf={(p) => `${p.kind}:${p.name}:${p.lat}`} render={(p) => ({ title: p.name, sub: p.address, badge: KIND_LABEL[p.kind] })}
      onPick={(p) => set({ [key]: encodePlace(p) })} />
  );

  return (
    <Layout title={`${from?.name ?? ""}→${to?.name ?? ""} 도로 분석`}>
      <PageHero eyebrow="도로 분석 · 전국 어디든 (고속도로 · 국도 · 일반도로)" title={from && to ? `${from.name} → ${to.name}` : " "}
                sub={<>카카오 미래 운행 정보로 본 {hm(d?.departAt)} 출발 경로 · 직선 {num(d?.straightKm, 0)}km</>}>
        <div className="flex flex-col items-center justify-center gap-2 sm:flex-row">
          {picker("어디서", from, "from")}
          <button className="chip h-11 w-11 shrink-0 text-base" aria-label="어디서와 어디로 바꾸기"
                  onClick={() => from && to && set({ from: encodePlace(to), to: encodePlace(from) })}>⇄</button>
          {picker("어디로", to, "to")}
        </div>
        <div className="mt-3 flex justify-center"><Segmented label="출발 시점" value={departIn} onChange={(v) => set({ t: v })} options={DEPART} /></div>
        <div className="mt-12">
          <SpecStrip>
            <Spec value={recP.big} unit={recP.unit} label={`추천 경로 · ${rec?.distanceM ? num(rec.distanceM / 1000, 0) + "km" : DASH}`} tone="road" />
            <Spec value={motorway == null ? DASH : (motorway * 100).toFixed(0)} unit={motorway == null ? "" : "%"} label="고속도로 비율 (거리)" />
            <Spec value={avoP.big} unit={avoP.unit} label={`고속도로 회피 · ${avo?.distanceM ? num(avo.distanceM / 1000, 0) + "km" : DASH}`} />
            <Spec value={d?.bestDeparture ? hm(d.bestDeparture.departAt) : DASH} label={`가장 빠른 출발 · ${dur(d?.bestDeparture?.durationSec)}`} />
          </SpecStrip>
        </div>
      </PageHero>

      {a.loading && !d && <Loading label="경로를 여러 출발 시각으로 계산하는 중" />}
      <div className="mx-auto max-w-[1200px] px-4"><ErrorBox error={a.error} /></div>

      {d && (
        <>
          {d.monitored && (
            <div className="mx-auto max-w-[1200px] px-4 sm:px-8">
              <Link href={`/road/${d.monitored.corridorId}?dir=${d.monitored.direction}`}
                    className="tile flex items-center justify-between gap-4 p-5 hover:shadow-md">
                <span><span className="eyebrow">고속도로 실측이 있는 길</span><br />
                  <span className="text-sm font-medium">{d.monitored.name} — 영업소 구간 통행시간 추이 · 평소 대비 정체 · 예측 보기</span></span>
                <span aria-hidden>→</span>
              </Link>
            </div>
          )}

          <section className="relative mt-10 h-[520px] bg-cloud">
            <RouteMap layers={layers} interactive className="absolute inset-0 h-full w-full" label="경로 비교 지도" />
            <div className="pointer-events-none absolute left-4 top-4 sm:left-8 sm:top-8 rounded bg-white/95 px-4 py-3 shadow-tile">
              <p className="flex items-center gap-2 text-xs text-muted"><span className="inline-block h-[3px] w-5 bg-road" />추천 경로 {dur(rec?.durationSec)}</p>
              <p className="mt-1 flex items-center gap-2 text-xs text-muted"><span className="inline-block h-0 w-5 border-t-[3px] border-dashed border-faint" />고속도로 회피 {dur(avo?.durationSec)}</p>
            </div>
          </section>

          <Section eyebrow="출발 시각" title="언제 출발하면 빠를까" gray
                   desc="같은 경로 요청을 출발 시각만 바꿔 카카오 미래 운행 정보로 계산했습니다 (예측값).">
            {d.profile.some((p) => p.durationSec != null) ? (
              <div className="tile p-6">
                <SimpleBars height={260} color={ROAD} x="label" y="min"
                            data={d.profile.map((p) => ({ label: hm(p.departAt), min: p.durationSec == null ? null : Math.round(p.durationSec / 60) }))}
                            yFormat={(v) => `${v}분`} format={(v, row) => `${row.label} 출발 · ${durMin(v)}`} />
              </div>
            ) : <Empty>출발 시각별 예측을 가져오지 못했습니다.</Empty>}
          </Section>

          <Section eyebrow="도로 구성" title="어떤 도로로 가나요" wide
                   desc="카카오 추천 경로를 도로 이름별로 묶었습니다. 소통 상태는 출발 시각 기준 예측입니다.">
            <div className="grid gap-6 lg:grid-cols-2">
              {[rec, avo].map((r) => r && (
                <div key={r.label} className="tile p-6">
                  <p className="text-sm font-medium">{r.label} <span className="font-normal text-muted">· {dur(r.durationSec)} · {r.distanceM ? num(r.distanceM / 1000, 0) : DASH}km{r.tollFare ? ` · 통행료 ${r.tollFare.toLocaleString()}원` : ""}</span></p>
                  <div className="mt-4"><TypeBar r={r} /></div>
                  {r.slow.length > 0 && (
                    <div className="mt-5">
                      <p className="text-xs font-medium text-ink2">느린 구간</p>
                      <ul className="mt-1 space-y-1 text-xs text-muted">
                        {r.slow.map((s) => <li key={s.name + s.distanceM} className="flex justify-between"><span>{s.name}</span><span className="tabular"><Traffic s={s.traffic} /> · {num(s.distanceM / 1000)}km · {num(s.speedKmh, 0)}km/h</span></li>)}
                      </ul>
                    </div>
                  )}
                </div>
              ))}
            </div>
            {rec && rec.roads.length > 0 && (
              <div className="tile mt-6 overflow-x-auto">
                <table className="w-full">
                  <caption className="sr-only">추천 경로 주요 도로</caption>
                  <thead><tr><th className="th">도로</th><th className="th">종류</th><th className="th text-right">거리</th><th className="th text-right">시간</th><th className="th text-right">평균 속도</th><th className="th">소통</th></tr></thead>
                  <tbody>{rec.roads.map((r, i) => (
                    <tr key={r.name + i} className="hover:bg-mist">
                      <td className="td font-medium">{r.name}</td>
                      <td className="td"><span className="inline-flex items-center gap-1.5"><span className="h-2 w-2 rounded-full" style={{ background: TYPE_COLOR[r.type] }} aria-hidden />{r.type}</span></td>
                      <td className="td text-right">{num(r.distanceM / 1000)}km</td>
                      <td className="td text-right">{durMin(r.durationSec / 60)}</td>
                      <td className="td text-right">{num(r.speedKmh, 0)}km/h</td>
                      <td className="td"><Traffic s={r.traffic} /></td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            )}
            <Note>{d.note}</Note>
          </Section>
        </>
      )}
    </Layout>
  );
}
