import { useRouter } from "next/router";
import { useMemo, useState } from "react";
import Layout from "@/components/Layout";
import CorridorBar from "@/components/CorridorBar";
import { Heatmap, TravelChart } from "@/components/Charts";
import { Empty, ErrorBox, Loading, Note, PageHero, Section, Segmented, Spec, SpecStrip } from "@/components/ui";
import { qs, useApi } from "@/lib/api";
import { DASH, DIR_LABEL, dur, durParts, mdhm, num, pct } from "@/lib/format";
import type { Baseline, Dir, Series } from "@/lib/types";
import { useCorridors } from "@/lib/useCorridors";

const RANGES = [
  { value: "1d", label: "24시간", hours: 24, agg: "5m" },
  { value: "3d", label: "3일", hours: 72, agg: "5m" },
  { value: "7d", label: "7일", hours: 168, agg: "1h" },
  { value: "30d", label: "30일", hours: 720, agg: "1h" },
];

export default function RoadPage() {
  const router = useRouter();
  const cid = (router.query.corridor as string) || "SEL-DJN";
  const dir = ((router.query.dir as string) === "UP" ? "UP" : "DN") as Dir;
  const [range, setRange] = useState("1d");
  const r = RANGES.find((x) => x.value === range)!;
  const win = useMemo(() => {
    const to = new Date();
    return { from: new Date(to.getTime() - r.hours * 3600_000).toISOString(), to: to.toISOString() };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range, cid, dir]);

  const corridors = useCorridors();
  const corridor = corridors.data?.find((c) => c.id === cid);
  const ready = router.isReady;
  const series = useApi<Series>(ready ? `/api/v1/corridors/${cid}/road/series?${qs({ dir, agg: r.agg, ...win })}` : null);
  const baseline = useApi<Baseline>(ready ? `/api/v1/corridors/${cid}/road/baseline?dir=${dir}` : null);

  const pts = series.data?.points ?? [];
  const last = pts[pts.length - 1];
  const maxP = pts.reduce<null | (typeof pts)[number]>((m, p) => (!m || p.travelSec > m.travelSec ? p : m), null);
  const lastParts = durParts(last ? last.travelSec / 60 : null);
  const baseParts = durParts(last?.baselineP50Sec ? last.baselineP50Sec / 60 : null);
  const maxParts = durParts(maxP ? maxP.travelSec / 60 : null);
  const units = corridor?.road[dir]?.units ?? [];

  return (
    <Layout title={`${corridor?.name ?? cid} 고속도로 실측`}>
      <PageHero eyebrow={`고속도로 실측 분석 · 자주 오가는 길 · ${DIR_LABEL[dir]}`} title={corridor ? `${units[0]?.name ?? ""} → ${units[units.length - 1]?.name ?? ""}` : cid}
                sub={<>영업소 {units.length}곳을 잇는 {corridor?.road[dir]?.segments ?? DASH}개 구간 · {num(corridor?.road[dir]?.distanceKm, 0)}km 의 1종(소형차) 통행시간 합</>}>
        <CorridorBar base="road" corridors={corridors.data} cid={cid} dir={dir} />
        <div className="mt-12">
          <SpecStrip>
            <Spec value={lastParts.big} unit={lastParts.unit} label={`최근 관측 · ${last ? mdhm(last.t) : DASH}`} tone="road" />
            <Spec value={baseParts.big} unit={baseParts.unit} label="같은 요일·시간 p50" />
            <Spec value={maxParts.big} unit={maxParts.unit} label={`기간 최대 · ${maxP ? mdhm(maxP.t) : DASH}`} />
            <Spec value={series.data?.stats.expectedPoints ? pct(series.data.stats.points / series.data.stats.expectedPoints) : DASH} label="기간 슬롯 완전성" />
          </SpecStrip>
        </div>
      </PageHero>

      <Section eyebrow="추이" title="통행시간과 기준선" wide
               desc={<>파란 선이 관측, 회색 점선이 같은 요일·시간대 최근 8주 중앙값입니다. 옅은 파란 음영은 출발지 강수 예보(강수확률 60% 이상 또는 강수 형태 있음) 시간 — 상관이지 인과가 아닙니다.</>}>
        <div className="mb-6 flex justify-center"><Segmented label="기간" value={range} onChange={setRange} options={RANGES} /></div>
        <ErrorBox error={series.error} />
        {series.loading && !series.data && <Loading />}
        {series.data && (pts.length ? <TravelChart series={series.data} /> : <Empty>이 기간에 저장된 슬롯이 없습니다.</Empty>)}
        {series.data && <Note>{series.data.note} 보정(FILLED) 슬롯 비율 {pct(series.data.stats.filledShare)}.</Note>}
      </Section>

      <Section eyebrow="기준선" title="요일 × 시간 히트맵" gray wide
               desc={<>최근 8주 같은 요일·5분 슬롯의 중앙값(p50)을 시간 단위로 평균했습니다. 데이터가 쌓이기 전에는 빈칸이 많습니다 — 값이 없으면 없다고 표시합니다.
                 {baseline.data?.windowFrom && <> 입력 기간 {baseline.data.windowFrom} ~ {baseline.data.windowTo}.</>}</>}>
        <ErrorBox error={baseline.error} />
        {baseline.data && (baseline.data.cells.length ? <div className="tile p-6"><Heatmap baseline={baseline.data} /></div> : <Empty>기준선이 아직 계산되지 않았습니다.</Empty>)}
      </Section>

      <Section eyebrow="구간" title="영업소 체인">
        <ol className="flex flex-wrap items-center justify-center gap-y-3 text-sm">
          {units.map((u, i) => (
            <li key={u.code + i} className="flex items-center">
              <span className={`rounded px-3 py-1.5 ${i === 0 || i === units.length - 1 ? "bg-ink text-white" : "bg-cloud text-ink2"}`}>{u.name}</span>
              {i < units.length - 1 && <span className="mx-1 text-faint" aria-hidden>—</span>}
            </li>
          ))}
        </ol>
        <Note>체인은 tools/build_seed.py 가 도로공사 realUnitTrtm 의 영업소 쌍별 표본 수를 확인해 자동으로 고릅니다. 인접 영업소 쌍 중에는 매칭 데이터가 없는 쌍이 있어(예: 기흥→오산) 고정 체인은 구멍이 생깁니다.
          품질 규칙 Q-v2 · 튀는 값 제거 H-v1 적용 후 합산합니다.</Note>
      </Section>
    </Layout>
  );
}
