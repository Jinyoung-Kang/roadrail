import { useRouter } from "next/router";
import { useEffect, useState } from "react";
import Layout from "@/components/Layout";
import CorridorBar from "@/components/CorridorBar";
import { C, SimpleBars } from "@/components/Charts";
import { Empty, ErrorBox, Loading, Note, PageHero, Section, Segmented, Select, Spec, SpecStrip } from "@/components/ui";
import { qs, useApi } from "@/lib/api";
import { DASH, DIR_LABEL, DOW, durMin, hm, num, pct, ymd } from "@/lib/format";
import type { Dir, Punctuality, Trains } from "@/lib/types";
import { useCorridors } from "@/lib/useCorridors";

const PERIODS = [{ value: 30, label: "최근 30일" }, { value: 90, label: "최근 90일" }];
const THRESHOLDS = [{ value: 3, label: "정시 ≤3분" }, { value: 5, label: "≤5분" }, { value: 10, label: "≤10분" }];

export default function RailPage() {
  const router = useRouter();
  const cid = (router.query.corridor as string) || "SEL-DJN";
  const dir = ((router.query.dir as string) === "UP" ? "UP" : "DN") as Dir;
  const [days, setDays] = useState(30);
  const [thr, setThr] = useState(5);
  const [date, setDate] = useState<string | null>(null);
  const [all, setAll] = useState(false);
  useEffect(() => setDate(null), [cid, dir]);

  const corridors = useCorridors();
  const corridor = corridors.data?.find((c) => c.id === cid);
  const to = ymd(new Date(Date.now() - 86400_000));
  const from = ymd(new Date(Date.now() - days * 86400_000));
  const base = router.isReady ? { corridorId: cid, dir, from, to, thresholdMin: thr } : null;
  const byTrain = useApi<Punctuality>(base ? `/api/v1/rail/punctuality?${qs({ ...base, groupBy: "train" })}` : null);
  const byDow = useApi<Punctuality>(base ? `/api/v1/rail/punctuality?${qs({ ...base, groupBy: "dow" })}` : null);
  const byHour = useApi<Punctuality>(base ? `/api/v1/rail/punctuality?${qs({ ...base, groupBy: "hour" })}` : null);
  const trains = useApi<Trains>(router.isReady ? `/api/v1/corridors/${cid}/rail/trains?${qs({ dir, date })}` : null);

  const s = byTrain.data?.summary;
  const nat = byTrain.data?.nationwideExact;
  const rail = corridor?.rail[dir];

  return (
    <Layout title={`${corridor?.name ?? cid} 철도 분석`}>
      <PageHero eyebrow={`철도 분석 · ${DIR_LABEL[dir]}`} title={rail ? `${rail.dep.name}역 → ${rail.arr.name}역` : cid}
                sub={<>코레일 운행계획 × 운행정보로 계산한 정시성 · {from} ~ {to}</>}>
        <CorridorBar base="rail" corridors={corridors.data} cid={cid} dir={dir}>
          <Segmented label="기간" value={days} onChange={setDays} options={PERIODS} />
          <Segmented label="정시 기준" value={thr} onChange={setThr} options={THRESHOLDS} />
        </CorridorBar>
        <div className="mt-12">
          <SpecStrip>
            <Spec value={s?.onTimeRate == null ? DASH : (s.onTimeRate * 100).toFixed(1)} unit={s?.onTimeRate == null ? "" : "%"} label={`정시율 (도착 ≤${thr}분)`} tone="rail" />
            <Spec value={num(s?.avgArrDelayMin)} unit="분" label="평균 도착 지연" />
            <Spec value={num(s?.p90ArrDelayMin)} unit="분" label="도착 지연 p90" />
            <Spec value={s ? s.verified.toLocaleString() : DASH} unit="회" label={`검증 운행 (확인 불가 ${s?.unverified ?? DASH})`} />
          </SpecStrip>
          {nat && <p className="mt-6 text-xs text-muted">비교: 같은 기간 전국 여객열차 종착역 기준(정확 비교 P-v1) 정시율 {pct(nat.onTimeRate, 1)} · 평균 지연 {num(nat.avgArrDelayMin)}분 · {nat.verified.toLocaleString()}회</p>}
        </div>
      </PageHero>

      <Section eyebrow="분포" title="도착 지연 분포와 패턴" wide>
        <ErrorBox error={byTrain.error} />
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="tile p-6">
            <p className="text-sm font-medium">도착 지연 분포 <span className="font-normal text-muted">(운행 수)</span></p>
            {byTrain.data ? <SimpleBars data={byTrain.data.histogram} x="label" y="count" color={C.rail} format={(v) => `${v}회`} /> : <Loading />}
          </div>
          <div className="tile p-6">
            <p className="text-sm font-medium">요일별 정시율</p>
            {byDow.data ? <SimpleBars data={byDow.data.items.map((i) => ({ ...i, label: DOW[Number(i.key)], rate: i.onTimeRate == null ? null : i.onTimeRate * 100 }))}
                                      x="label" y="rate" color={C.rail} yFormat={(v) => `${v}%`}
                                      format={(v, d) => `정시율 ${num(v, 1)}% · 평균 지연 ${num(d.avgArrDelayMin)}분 · ${d.verified}회`} /> : <Loading />}
          </div>
          <div className="tile p-6">
            <p className="text-sm font-medium">출발 시간대별 정시율</p>
            {byHour.data ? <SimpleBars data={byHour.data.items.map((i) => ({ ...i, label: `${Number(i.key)}`, rate: i.onTimeRate == null ? null : i.onTimeRate * 100 }))}
                                       x="label" y="rate" color={C.rail} yFormat={(v) => `${v}%`}
                                       format={(v, d) => `${d.label}시 출발 · 정시율 ${num(v, 1)}% · ${d.verified}회`} /> : <Loading />}
          </div>
        </div>
        {byTrain.data && <Note>{byTrain.data.note} {Object.entries(byTrain.data.rules).map(([k, v]) => `${k}: ${v}`).join(" · ")}</Note>}
      </Section>

      <Section eyebrow="열차별" title="정시율 랭킹" gray wide desc="표본이 많은 열차부터. ⚠ 는 중간역 지연을 보간 추정한 비율이 있는 열차입니다.">
        {byTrain.data && byTrain.data.items.length === 0 && <Empty>이 기간 운행 기록이 없습니다.</Empty>}
        {byTrain.data && byTrain.data.items.length > 0 && (
          <div className="tile overflow-x-auto">
            <table className="w-full">
              <thead><tr>
                <th className="th">열차</th><th className="th text-right">운행</th><th className="th text-right">정시율</th>
                <th className="th text-right">평균 지연</th><th className="th text-right">p90</th><th className="th text-right">평균 소요</th><th className="th text-right">추정 비율</th>
              </tr></thead>
              <tbody>
                {byTrain.data.items.slice(0, 40).map((i) => (
                  <tr key={i.key} className="hover:bg-mist">
                    <td className="td font-medium">{i.key.replace(/^0+/, "")}</td>
                    <td className="td text-right">{i.verified}/{i.samples}</td>
                    <td className="td text-right">
                      <span className="inline-flex items-center gap-2">
                        <span className="h-1.5 w-16 overflow-hidden rounded-full bg-cloud"><span className="block h-full rounded-full bg-rail" style={{ width: `${(i.onTimeRate ?? 0) * 100}%` }} /></span>
                        {pct(i.onTimeRate)}
                      </span>
                    </td>
                    <td className="td text-right">{num(i.avgArrDelayMin)}분</td>
                    <td className="td text-right">{num(i.p90ArrDelayMin)}분</td>
                    <td className="td text-right">{durMin(i.avgRideMin)}</td>
                    <td className="td text-right">{i.estimatedShare ? `⚠ ${pct(i.estimatedShare)}` : "정확"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Section eyebrow="운행표" title="날짜별 운행" wide>
        <div className="mb-6 flex justify-center">
          <Select label="운행일" value={date ?? trains.data?.date ?? ""} onChange={setDate}
                  options={(trains.data?.availableDates ?? []).map((d) => ({ value: d, label: d }))} />
        </div>
        <ErrorBox error={trains.error} />
        {trains.data && (trains.data.trains.length ? (
          <div className="tile overflow-x-auto">
            <table className="w-full">
              <thead><tr>
                <th className="th">열차</th><th className="th">계획 출발</th><th className="th">실제 출발</th><th className="th">계획 도착</th><th className="th">실제 도착</th>
                <th className="th text-right">출발 지연</th><th className="th text-right">도착 지연</th><th className="th text-right">소요</th><th className="th">정시</th><th className="th text-right">30일 정시율</th>
              </tr></thead>
              <tbody>
                {(all ? trains.data.trains : trains.data.trains.slice(0, 40)).map((t) => (
                  <tr key={t.trnNo} className="hover:bg-mist">
                    <td className="td font-medium">{t.trnNo.replace(/^0+/, "")}</td>
                    <td className="td">{hm(t.planDepAt)}{t.depBasis === "EST" && " ⚠"}</td><td className="td">{hm(t.actDepAt)}</td>
                    <td className="td">{hm(t.planArrAt)}{t.arrBasis === "EST" && " ⚠"}</td><td className="td">{hm(t.actArrAt)}</td>
                    <td className="td text-right">{num(t.depDelayMin)}분</td><td className="td text-right">{num(t.arrDelayMin)}분</td>
                    <td className="td text-right">{durMin(t.rideMin)}</td>
                    <td className="td">{t.onTime === null ? DASH : t.onTime ? <span><span className="text-good">●</span> 정시</span> : <span><span className="text-crit">✕</span> 지연</span>}</td>
                    <td className="td text-right">{pct(t.stats30d?.onTimeRate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <Empty>이 날짜의 운행 기록이 없습니다.</Empty>)}
        {trains.data && trains.data.trains.length > 40 && (
          <div className="mt-6 flex justify-center">
            <button className="btn-secondary" onClick={() => setAll(!all)}>{all ? "접기" : `전체 ${trains.data.trains.length}편 보기`}</button>
          </div>
        )}
        {trains.data && <Note>{trains.data.note}</Note>}
      </Section>
    </Layout>
  );
}
