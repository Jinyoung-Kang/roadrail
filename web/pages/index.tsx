import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, useMemo } from "react";
import Layout from "@/components/Layout";
import RouteMap from "@/components/RouteMap";
import SearchPicker from "@/components/SearchPicker";
import { CarTile, EnvRow, Evidence, TrainTile } from "@/components/Tiles";
import { Empty, ErrorBox, Loading, Section, Segmented, Select, Spec, SpecStrip } from "@/components/ui";
import { qs, useApi } from "@/lib/api";
import { DASH, durParts, hm, mdhm, num } from "@/lib/format";
import { tripLayers } from "@/lib/layers";
import { corridorEnds, decodePlace, encodePlace, KIND_LABEL, suggestedPlaces } from "@/lib/places";
import type { Place, Trip } from "@/lib/types";
import { useCorridors } from "@/lib/useCorridors";

const DEPART = [0, 30, 60, 120, 180].map((v) => ({ value: v, label: v === 0 ? "지금" : `+${v >= 60 ? `${v / 60}시간` : `${v}분`}` }));
const ACCESS = [{ value: "auto", label: "역까지 실제 경로" }, ...[10, 20, 30, 45].map((v) => ({ value: String(v), label: `역까지 ${v}분` }))];

export default function Home() {
  const router = useRouter();
  const q = router.query;
  const corridors = useCorridors();
  const departIn = Number(q.t ?? 0) || 0;
  const access = (q.a as string) || "auto";

  // 어디서 · 어디로 — URL 이 없으면 첫 번째 길(서울역 → 대전역)
  const fallback = useMemo(() => (corridors.data?.[0] ? corridorEnds(corridors.data[0]) : null), [corridors.data]);
  const from = decodePlace(q.from) ?? fallback?.[0] ?? null;
  const to = decodePlace(q.to) ?? fallback?.[1] ?? null;

  const set = (p: Record<string, string | number>) =>
    router.replace({ pathname: "/", query: { ...q, ...(from && !q.from ? { from: encodePlace(from) } : {}),
      ...(to && !q.to ? { to: encodePlace(to) } : {}), ...p } }, undefined, { shallow: true, scroll: false });

  const url = router.isReady && from && to ? `/api/v1/trip?${qs({
    fromLat: from.lat, fromLon: from.lon, fromName: from.name, fromStation: from.stationCode,
    toLat: to.lat, toLon: to.lon, toName: to.name, toStation: to.stationCode,
    departIn, accessMin: access === "auto" ? undefined : access })}` : null;
  const trip = useApi<Trip>(url, 60_000);
  const t = trip.data && from && trip.data.from.name === from.name && trip.data.to.name === to?.name ? trip.data : null;

  // 카카오 경로·날씨가 아직 오는 중이면 잠시 뒤 다시 (서버는 조회를 계속해 캐시를 채운다)
  useEffect(() => {
    if (!trip.data?.pending) return;
    const id = setTimeout(trip.reload, 1500);
    return () => clearTimeout(id);
  }, [trip.data, trip.reload]);

  const d = t?.decision;
  const car = durParts(d?.carTotalMin ?? (t?.car.durationSec ? t.car.durationSec / 60 : null));
  const train = durParts(d?.trainTotalMin);
  const suggestions = useMemo(() => suggestedPlaces(corridors.data), [corridors.data]);
  const layers = useMemo(() => tripLayers(t, from, to), [t, from, to]);
  const picker = (label: string, value: Place | null, key: "from" | "to") => (
    <SearchPicker<Place> label={label} placeholder="지역 · 역 · 장소 검색" value={value?.name ?? ""} className="w-full sm:w-[300px]"
      search={(term) => `/api/v1/places/search?q=${encodeURIComponent(term)}`} suggestions={suggestions}
      keyOf={(p) => `${p.kind}:${p.name}:${p.lat}`} render={(p) => ({ title: p.name, sub: p.address, badge: KIND_LABEL[p.kind] })}
      onPick={(p) => set({ [key]: encodePlace(p) })} />
  );

  return (
    <Layout title="지금 차로 갈까, 기차로 갈까" overlay>
      {/* ---------- 히어로: 지도 배경 + 어디서 → 어디로 + 스펙 + 두 버튼 */}
      <section className="relative z-10 h-[100svh] min-h-[720px] bg-[#eef1f4]">
        <RouteMap layers={layers} className="absolute inset-0 h-full w-full" padBottom={120} label="경로 지도" />
        <div className="pointer-events-none absolute inset-x-0 top-0 h-[50%] bg-gradient-to-b from-white via-white/85 to-transparent" />
        <div className="pointer-events-none absolute inset-x-0 bottom-0 h-[44%] bg-gradient-to-t from-white via-white/90 to-transparent" />

        <div className="relative mx-auto max-w-[1200px] px-4 pt-[13vh] text-center">
          <p className="eyebrow">{hm(t?.departAt)} 출발 기준 · 직선 {num(t?.distanceKm, 0)}km</p>
          <h1 className="mt-2 text-[34px] sm:text-[48px] font-medium tracking-tight text-ink">
            {from?.name ?? " "} <span className="text-faint">→</span> {to?.name ?? " "}
          </h1>
          <p className="mt-2 min-h-[24px] text-[15px] sm:text-[17px] text-ink2">
            {trip.loading && !t ? "판단 중…" : d?.summary ?? (trip.error ? "판단 카드를 불러오지 못했습니다" : "")}
            {d && <a href="#evidence" className="ml-2 underline underline-offset-4 text-ink">근거 보기</a>}
          </p>
          <div className="mt-6 flex flex-col items-center justify-center gap-2 sm:flex-row">
            {picker("어디서", from, "from")}
            <button className="chip h-11 w-11 shrink-0 text-base" aria-label="어디서와 어디로 바꾸기"
                    onClick={() => from && to && set({ from: encodePlace(to), to: encodePlace(from) })}>⇄</button>
            {picker("어디로", to, "to")}
          </div>
          <div className="mt-3 flex flex-wrap items-center justify-center gap-2">
            <Segmented label="출발 시점" value={departIn} onChange={(v) => set({ t: v })} options={DEPART} />
            <Select label="역까지 걸리는 시간" value={access} onChange={(v) => set({ a: v })} options={ACCESS} />
          </div>
        </div>

        <div className="absolute inset-x-0 bottom-0 pb-10 sm:pb-14">
          <SpecStrip>
            <Spec value={t?.car.pending && car.big === DASH ? "…" : car.big} unit={car.unit} label="자동차" tone="road" />
            <Spec value={train.big} unit={train.unit} label="기차" tone="rail" />
            <Spec value={d?.diffMin == null ? DASH : String(Math.abs(d.diffMin))} unit={d?.diffMin == null ? "" : "분"}
                  label={d?.verdict === "TRAIN" ? "기차가 빠름" : d?.verdict === "CAR" ? "자동차가 빠름" : "차이"} />
          </SpecStrip>
          <div className="mt-8 flex flex-col items-center justify-center gap-3 px-4 sm:flex-row sm:gap-6">
            <a href="#compare" className="btn-primary">자세히 보기</a>
            {from && to && <Link href={`/road?from=${encodeURIComponent(encodePlace(from))}&to=${encodeURIComponent(encodePlace(to))}`} className="btn-secondary">경로 분석</Link>}
          </div>
        </div>
      </section>

      {/* ---------- 비교 */}
      <Section id="compare" eyebrow="자동차와 기차" title="같은 출발 시각, 두 가지 선택" gray
               desc="자동차는 도로(고속도로·국도·일반도로) 기준 카카오 경로 예측, 기차는 가까운 역에서 목적지 가까운 역까지 코레일 환승 경로와 최근 30일 실제 운행으로 계산합니다.">
        <ErrorBox error={trip.error} />
        {!t && trip.loading && <Loading />}
        {t && <div className="grid gap-6 lg:grid-cols-2"><CarTile trip={t} /><TrainTile trip={t} /></div>}
      </Section>

      <Section id="evidence" eyebrow="R-DEC-01" title="왜 이렇게 판단했나요">
        {t ? <Evidence decision={t.decision} freshness={t.freshness} caveat={t.caveat} cache={t.cache} asOf={t.asOf} /> : <Loading />}
      </Section>

      <Section eyebrow="날씨 · 대기" title="어디서와 어디로" gray>
        {t ? <EnvRow env={t.env} /> : <Loading />}
      </Section>

      {t?.observed && (
        <Section eyebrow="돌발 안내" title={`${t.observed.corridorName} 길 · 최근 6시간`}>
          {t.incidents.length === 0 ? <Empty>매칭된 돌발 안내가 없습니다.</Empty> : (
            <ul className="space-y-3">
              {t.incidents.map((i) => (
                <li key={i.sentAt + i.content} className="tile flex gap-4 p-5">
                  <div className="w-24 flex-none text-sm tabular text-muted">{mdhm(i.sentAt)}</div>
                  <div>
                    <div className="text-sm font-medium">{i.routeName} · {i.typeName} <span className="font-normal text-muted">{i.direction} · {i.process}</span></div>
                    <p className="mt-1 text-sm text-ink2">{i.content}</p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}

      <section className="relative h-[560px] bg-cloud">
        <RouteMap layers={layers} interactive className="absolute inset-0 h-full w-full" label="경로 지도 (확대·이동 가능)" />
        <div className="pointer-events-none absolute left-4 top-4 sm:left-8 sm:top-8 rounded bg-white/95 px-4 py-3 shadow-tile">
          <p className="text-sm font-medium">{from?.name} → {to?.name}</p>
          <p className="mt-1 flex items-center gap-2 text-xs text-muted"><span className="inline-block h-[3px] w-5 bg-road" />자동차 경로 (카카오) {t?.car.distanceM ? `${num(t.car.distanceM / 1000, 0)}km` : ""}</p>
          <p className="mt-1 flex items-center gap-2 text-xs text-muted"><span className="inline-block h-0 w-5 border-t-[3px] border-dashed border-rail" />기차 {t?.rail?.journeys[0] ? t.rail.journeys[0].legs.map((l, i) => (i === 0 ? `${l.fromName}→${l.toName}` : `→${l.toName}`)).join("") : "없음"}</p>
        </div>
      </section>

      <Section eyebrow="길" title="자주 오가는 길" wide
               desc="이 길들은 고속도로 영업소 구간 통행시간을 10분마다 모아 평소 대비 정체와 예측까지 보여 줍니다. 다른 곳은 위 검색으로 전국 어디든 고를 수 있습니다.">
        <ErrorBox error={corridors.error} />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {(corridors.data ?? []).map((c) => {
            const ends = corridorEnds(c);
            const on = ends && from?.stationCode === ends[0].stationCode && to?.stationCode === ends[1].stationCode;
            return (
              <button key={c.id} disabled={!ends}
                      onClick={() => { if (ends) { set({ from: encodePlace(ends[0]), to: encodePlace(ends[1]) }); window.scrollTo({ top: 0, behavior: "smooth" }); } }}
                      className={`tile p-6 text-left transition hover:shadow-md ${on ? "ring-2 ring-ink" : ""}`}>
                <p className="eyebrow">{c.id}</p>
                <p className="mt-1 text-xl font-medium">{c.name}</p>
                <p className="mt-3 text-sm text-muted">고속도로 {num(c.road.DN?.distanceKm, 0)}km · 구간 {c.road.DN?.segments}개</p>
                <p className="text-sm text-muted">철도 {c.rail.DN?.dep.name}역 – {c.rail.DN?.arr.name}역</p>
              </button>
            );
          })}
        </div>
      </Section>
    </Layout>
  );
}
