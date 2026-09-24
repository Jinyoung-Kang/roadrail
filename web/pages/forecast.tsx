import { useState } from "react";
import Layout from "@/components/Layout";
import { MaeChart } from "@/components/Charts";
import { Empty, ErrorBox, Loading, Note, PageHero, Section, Segmented, Select } from "@/components/ui";
import { useApi } from "@/lib/api";
import { DASH, dur, durMin, hm, mdhm, num, pct } from "@/lib/format";
import type { Dir, Forecast } from "@/lib/types";
import { useCorridors } from "@/lib/useCorridors";

const MODELS = [
  { key: "M0", name: "M0 기준선", desc: "같은 요일·시간 최근 8주 중앙값" },
  { key: "M1", name: "M1 기준선+편차", desc: "현재 편차가 e^(−h/90분)로 풀린다고 가정" },
  { key: "persistence", name: "지속", desc: "지금 값이 그대로 유지 (비교용 최소 기준)" },
] as const;

export default function ForecastPage() {
  const [cid, setCid] = useState("SEL-DJN");
  const [dir, setDir] = useState<Dir>("DN");
  const corridors = useCorridors();
  const c = corridors.data?.find((x) => x.id === cid);
  const f = useApi<Forecast>(`/api/v1/corridors/${cid}/road/forecast?dir=${dir}&horizons=60,120,180`);
  const bt = f.data?.backtest;

  return (
    <Layout title="예측 성능">
      <PageHero eyebrow="예측 성능 · 백테스트" title="해석 가능한 모델부터"
                sub="기준선 모델(M0·M1)이 '지금 값 그대로(지속)'보다 나은지 숫자로 확인합니다.">
        <div className="flex flex-wrap items-center justify-center gap-2">
          <Select label="코리도" value={cid} onChange={setCid} options={(corridors.data ?? []).map((x) => ({ value: x.id, label: x.name }))} />
          <Segmented label="방향" value={dir} onChange={setDir}
                     options={[{ value: "DN" as Dir, label: c ? `${c.originCity}→${c.destCity}` : "하행" },
                               { value: "UP" as Dir, label: c ? `${c.destCity}→${c.originCity}` : "상행" }]} />
        </div>
      </PageHero>

      <Section eyebrow="지금부터" title="60 · 120 · 180분 뒤 통행시간" gray
               desc={<>마지막 관측 슬롯 {f.data?.lastObservedSlot ? mdhm(f.data.lastObservedSlot) : DASH} ({dur(f.data?.lastObservedSec)}) 에서 출발합니다.
                 도로공사 공개 지연 때문에 실제 선행시간(lead)은 horizon 보다 깁니다.</>}>
        <ErrorBox error={f.error} />
        {f.loading && !f.data && <Loading />}
        {f.data && (f.data.items.length === 0 ? <Empty>관측 데이터가 없어 예측할 수 없습니다.</Empty> : (
          <div className="grid gap-6 md:grid-cols-3">
            {f.data.items.map((it) => (
              <div key={it.horizonMin} className="tile p-6">
                <p className="eyebrow">{it.horizonMin}분 뒤 · {hm(it.targetAt)} 출발</p>
                <p className="mt-1 text-xs text-muted">선행 {durMin(it.leadMin)} · 기준선 표본 {it.baselineN}일
                  {it.baselineN > 0 && it.baselineN < 4 && <span title="표본 4일 미만 — 참고용"> ⚠</span>}</p>
                <dl className="mt-4">
                  {MODELS.map((m) => (
                    <div key={m.key} className="flex items-baseline justify-between border-b border-line py-2.5 last:border-0">
                      <dt className="text-sm text-muted">{m.name}</dt>
                      <dd className="text-lg font-medium tabular">{dur((it as any)[m.key])}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            ))}
          </div>
        ))}
        <div className="mt-8 grid gap-4 text-sm sm:grid-cols-3">
          {MODELS.map((m) => <div key={m.key} className="text-center"><p className="font-medium">{m.name}</p><p className="text-muted">{m.desc}</p></div>)}
        </div>
      </Section>

      <Section eyebrow="백테스트" title="horizon 별 평균 절대 오차 (MAE)" wide
               desc={<>매 정시에 발행했다고 가정하고 horizon 분 뒤 실제값과 비교했습니다. 발행일마다 그 이전 8주 데이터로만 기준선을 다시 만들어 미래 정보가 섞이지 않게 했습니다.
                 {bt?.evalDate && <> 평가일 {bt.evalDate} · 기간 {bt.period} · {bt.modelVersion}.</>}</>}>
        {bt && bt.cells.length === 0 && <Empty>아직 백테스트할 데이터가 충분히 쌓이지 않았습니다 (수집 시작 후 하루 이상 필요).</Empty>}
        {bt && bt.cells.length > 0 && (
          <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
            <div className="tile p-6"><MaeChart cells={bt.cells} /></div>
            <div className="tile overflow-x-auto">
              <table className="w-full">
                <caption className="sr-only">백테스트 표</caption>
                <thead><tr><th className="th">horizon</th><th className="th">모델</th><th className="th text-right">MAE</th><th className="th text-right">MAPE</th><th className="th text-right">n</th></tr></thead>
                <tbody>
                  {bt.cells.map((cl) => (
                    <tr key={cl.horizonMin + cl.model}>
                      <td className="td">{cl.horizonMin}분</td>
                      <td className="td">{MODELS.find((m) => m.key === cl.model)?.name ?? cl.model}</td>
                      <td className="td text-right">{cl.maeSec == null ? DASH : `${num(cl.maeSec / 60)}분`}</td>
                      <td className="td text-right">{pct(cl.mape, 1)}</td>
                      <td className="td text-right">{cl.n}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
        {f.data && <Note>{f.data.note}</Note>}
      </Section>
    </Layout>
  );
}
