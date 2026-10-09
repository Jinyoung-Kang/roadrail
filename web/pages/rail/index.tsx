import Layout from "@/components/Layout";
import SearchPicker from "@/components/SearchPicker";
import { SimpleBars } from "@/components/LazyCharts";
import { C } from "@/lib/palette";
import { Empty, ErrorBox, Loading, Note, PageHero, Section, Segmented, Select, Spec, SpecStrip, TrainName } from "@/components/ui";
import { api } from "@/lib/api/client";
import { useRailOd } from "@/lib/hooks/useRailOd";
import { DASH, durMin, hm, num, pct } from "@/lib/format";
import { BAND_PERIODS, bandRows, bandText, dowBars, hourBars, mostlyUnplanned, PERIODS, THRESHOLDS, trainsOverThreshold } from "@/lib/rail";
import type { Station } from "@/lib/types";

export default function RailPage() {
  const { dep, arr, go, days, setDays, thr, setThr, date, setDate, all, setAll, period, allStations, byTrain, byDow, byHour, trains,
    ttPending, depName, arrName, bandDays, setBandDays, bands } = useRailOd();
  const s = byTrain.data?.summary;
  const nat = byTrain.data?.nationwideExact;
  const stationPicker = (label: string, value: string | undefined, key: "dep" | "arr") => (
    <SearchPicker<Station> label={label} placeholder="역 이름 검색" value={value ? `${value}역` : ""} className="w-full sm:w-[260px]"
      search={(t) => api.stations({ limit: 60, sort: "name", q: t.replace(/역$/, "") })}
      suggestions={allStations.data ?? []} keyOf={(st) => st.code}
      render={(st) => ({ title: `${st.name}역`, sub: `최근 7일 ${st.trains7d.toLocaleString()}회 정차` })}
      onPick={(st) => go({ [key]: st.code })} />
  );

  return (
    <Layout title={`${depName ?? ""}→${arrName ?? ""} 철도 분석`}>
      <PageHero eyebrow="철도 분석 · 전국 모든 역 쌍 (직통)" title={depName && arrName ? `${depName}역 → ${arrName}역` : " "}
                sub={<>코레일 운행계획 × 운행정보로 계산한 정시성<br className="sm:hidden" />{period ? <span><span className="hidden sm:inline"> · </span>{period.from} ~ {period.to}</span> : "\u00a0"}</>}>
        <div className="flex flex-col items-center justify-center gap-2 sm:flex-row">
          {stationPicker("출발역", depName, "dep")}
          <button className="chip h-11 w-11 shrink-0 text-base" aria-label="출발역과 도착역 바꾸기" onClick={() => go({ dep: arr, arr: dep })}>⇄</button>
          {stationPicker("도착역", arrName, "arr")}
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-center gap-2">
          <Segmented label="기간" value={days} onChange={setDays} options={PERIODS} />
          <Segmented label="정시 기준" value={thr} onChange={setThr} options={THRESHOLDS} />
        </div>
        {dep === arr && <p className="mt-4 text-sm text-crit">출발역과 도착역이 같습니다.</p>}
        {s && s.samples === 0 && <p className="mt-6 text-sm text-muted">이 기간 두 역을 잇는 직통 운행이 없습니다 (환승 경로는 다루지 않습니다).</p>}
        <div className="mt-12">
          <SpecStrip>
            <Spec value={s?.onTimeRate == null ? DASH : (s.onTimeRate * 100).toFixed(1)} unit={s?.onTimeRate == null ? "" : "%"} label={`정시율 (도착 ≤${thr}분)`} tone="rail" />
            <Spec value={num(s?.avgArrDelayMin)} unit="분" label="평균 도착 지연" />
            <Spec value={num(s?.p90ArrDelayMin)} unit="분" label="도착 지연 p90" />
            <Spec value={s ? s.verified.toLocaleString() : DASH} unit="회" label={`검증 운행 (확인 불가 ${s?.unverified ?? DASH})`} />
          </SpecStrip>
          {ttPending && <p className="mt-6 text-xs text-muted" role="status"><span className="mr-1 inline-block h-3 w-3 animate-spin rounded-full border-2 border-line border-t-ink align-[-2px]" aria-hidden />
            TAGO 열차 시간표를 받는 중 — 받는 대로 다시 계산합니다 (그동안 시간표가 없는 중간역 운행은 확인 불가로 제외)</p>}
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
            <p className="text-sm font-medium">요일별 정시율 <span className="font-normal text-muted">(공휴일은 따로)</span></p>
            {byDow.data ? <SimpleBars data={dowBars(byDow.data.items)}
                                      x="label" y="rate" color={C.rail} yFormat={(v) => `${v}%`}
                                      format={(v, d) => `정시율 ${num(v, 1)}% · 평균 지연 ${num(d.avgArrDelayMin)}분 · ${d.verified}회`} /> : <Loading />}
          </div>
          <div className="tile p-6">
            <p className="text-sm font-medium">출발 시간대별 정시율</p>
            {byHour.data ? <SimpleBars data={hourBars(byHour.data.items)}
                                       x="label" y="rate" color={C.rail} yFormat={(v) => `${v}%`}
                                       format={(v, d) => `${d.label}시 출발 · 정시율 ${num(v, 1)}% · ${d.verified}회`} /> : <Loading />}
          </div>
        </div>
        {byTrain.data && <Note>{byTrain.data.note} {Object.entries(byTrain.data.rules).map(([k, v]) => `${k}: ${v}`).join(" · ")}</Note>}
      </Section>

      <Section eyebrow="열차별" title="정시율 랭킹" gray wide desc="표본이 많은 열차부터. 차종은 TAGO 열차 시간표에 적힌 최근 운행일의 배정 차종입니다. 운행 = 계획 시각과 비교한 운행 / 전체 운행 (시간표가 없는 날의 중간역 운행은 확인 불가로 제외).">
        {byTrain.data && byTrain.data.items.length === 0 && <Empty>이 기간 운행 기록이 없습니다.</Empty>}
        {byTrain.data && byTrain.data.items.length > 0 && (
          <div className="tile overflow-x-auto">
            <table className="w-full">
              <thead><tr>
                <th className="th">열차</th><th className="th text-right">운행</th><th className="th text-right">정시율</th>
                <th className="th text-right">평균 지연</th><th className="th text-right">p90</th><th className="th text-right">평균 소요</th>
                <th className="th text-right" title="도착 지연 20분 이상(지연 배상 기준 시간) 운행 / 검증 운행">20분↑</th>
              </tr></thead>
              <tbody>
                {byTrain.data.items.slice(0, 40).map((i) => (
                  <tr key={i.key} className="hover:bg-mist">
                    <td className="td"><TrainName trnNo={i.key} meta={i.meta} grade={i.grade} /></td>
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
                    <td className="td text-right tabular">{bandText(i.delayBands) ?? DASH}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      <Section eyebrow="지연 배상 기준" title="배상 기준 시간을 넘긴 운행" wide
               desc="도착역 계획 도착보다 20분 이상 늦으면 지연 배상 대상이 될 수 있습니다(코레일 여객운송약관). 드문 일이라 횟수를 먼저 보이고, 검증 운행 15회 미만은 비율을 내지 않습니다.">
        <div className="mb-6 flex justify-center">
          <Segmented label="배상 통계 범위" value={bandDays} onChange={setBandDays} options={BAND_PERIODS} />
        </div>
        <ErrorBox error={bands.error} />
        {!bands.data && bands.loading && <Loading />}
        {bands.data && (
          <div className="grid items-start gap-6 lg:grid-cols-[1fr_1.2fr]">
            <div className="tile overflow-x-auto">
              <table className="w-full">
                <thead><tr><th className="th">도착 지연</th><th className="th text-right">이 역 쌍</th><th className="th text-right">전국 종착역</th></tr></thead>
                <tbody>{bandRows(bands.data.summary.delayBands, bands.data.nationwideExact.delayBands).map((r) => (
                  <tr key={r.threshold}><td className="td">{r.threshold}분 이상</td>
                    <td className="td text-right tabular">{r.pair ?? DASH}</td><td className="td text-right tabular">{r.nation ?? DASH}</td></tr>
                ))}</tbody>
              </table>
            </div>
            <div className="tile p-5">
              <p className="text-sm font-medium">20분 이상 늦은 적이 있는 열차</p>
              {trainsOverThreshold(bands.data.items).length === 0
                ? <p className="mt-2 text-sm text-muted">이 기간 20분 이상 늦은 검증 운행이 없습니다.</p>
                : <ul className="mt-2 divide-y divide-line text-sm">{trainsOverThreshold(bands.data.items).slice(0, 12).map((i) => (
                    <li key={i.key} className="flex items-center justify-between gap-3 py-2">
                      <TrainName trnNo={i.key} meta={i.meta} grade={i.grade} />
                      <span className="tabular text-ink2">{bandText(i.delayBands)}{i.delayBands.ge60 > 0 ? ` · 60분↑ ${i.delayBands.ge60}회` : ""}</span>
                    </li>
                  ))}</ul>}
            </div>
          </div>
        )}
        <Note>배상 여부는 지연 원인(천재지변 · 응급 구호 등 제외 사유)에 따라 달라 이 값과 다를 수 있습니다. 운행정보에는 지연 원인이 없습니다.
          배상률은 출처가 엇갈려(예: 60분 이상 50% ↔ 구간별 50·75·100%) 원문을 확인하기 전까지 표시하지 않습니다.</Note>
      </Section>

      <Section eyebrow="운행표" title="날짜별 운행" wide>
        <div className="mb-6 flex justify-center">
          <Select label="운행일" value={date ?? trains.data?.date ?? ""} onChange={setDate}
                  options={(trains.data?.availableDates ?? []).map((d) => ({ value: d, label: d }))} />
        </div>
        <ErrorBox error={trains.error} />
        {mostlyUnplanned(trains.data) && (
          <p className="mb-4 rounded-sm bg-mist px-4 py-3 text-center text-xs text-muted">
            이 날짜는 TAGO 열차 시간표가 제공되지 않아 중간역의 계획 시각을 알 수 없습니다 — 계획·지연은 '—' 로 두고 추정하지 않습니다.
          </p>
        )}
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
                    <td className="td"><TrainName trnNo={t.trnNo} meta={t.meta} grade={t.grade} /></td>
                    <td className="td">{hm(t.planDepAt)}</td><td className="td">{hm(t.actDepAt)}</td>
                    <td className="td">{hm(t.planArrAt)}</td><td className="td">{hm(t.actArrAt)}</td>
                    <td className="td text-right">{t.depDelayMin == null ? DASH : `${num(t.depDelayMin)}분`}</td>
                    <td className="td text-right">{t.arrDelayMin == null ? DASH : `${num(t.arrDelayMin)}분`}</td>
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
