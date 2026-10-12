import { useState } from "react";
import Layout from "@/components/Layout";
import { MaeChart } from "@/components/LazyCharts";
import { Empty, ErrorBox, Loading, Note, PageHero, Section, Segmented, Select } from "@/components/ui";
import { api } from "@/lib/api/client";
import { useApi } from "@/lib/hooks/useApi";
import { DASH, dur, durMin, hm, mdhm, num, pct } from "@/lib/format";
import type { Dir, Forecast } from "@/lib/types";
import { useCorridors } from "@/lib/hooks/useCorridors";
import { MODELS, modelVersionText } from "@/lib/forecast";


export default function ForecastPage() {
  const [cid, setCid] = useState("SEL-DJN");
  const [dir, setDir] = useState<Dir>("DN");
  const corridors = useCorridors();
  const c = corridors.data?.find((x) => x.id === cid);
  const f = useApi<Forecast>(api.corridorForecast(cid, dir, [60, 120, 180]));
  const bt = f.data?.backtest;

  return (
    <Layout title="예측 성능">
      <PageHero eyebrow="예측 성능 · 지난 기록으로 채점" title="설명할 수 있는 방법부터"
                sub="평소 값으로 하는 예측이 '지금 값 그대로'보다 나은지 숫자로 확인합니다.">
        <div className="flex flex-wrap items-center justify-center gap-2">
          <Select label="길" value={cid} onChange={setCid} options={(corridors.data ?? []).map((x) => ({ value: x.id, label: x.name }))} />
          <Segmented label="방향" value={dir} onChange={setDir}
                     options={[{ value: "DN" as Dir, label: c ? `${c.originCity}→${c.destCity}` : "하행" },
                               { value: "UP" as Dir, label: c ? `${c.destCity}→${c.originCity}` : "상행" }]} />
        </div>
      </PageHero>

      <Section eyebrow="지금부터" title="60 · 120 · 180분 뒤 통행시간" gray
               desc={[<>마지막 관측 {f.data?.lastObservedSlot ? mdhm(f.data.lastObservedSlot) : DASH}(그때 통행시간 {dur(f.data?.lastObservedSec)})에서 예측합니다.</>,
                 "도로공사가 값을 몇 시간 늦게 공개해, 실제로는 마지막 관측보다 더 먼 앞날을 예측하는 셈입니다."]}>
        <ErrorBox error={f.error} />
        {f.loading && !f.data && <Loading />}
        {f.data && (f.data.items.length === 0 ? <Empty>관측 데이터가 없어 예측할 수 없습니다.</Empty> : (
          <div className="grid gap-6 md:grid-cols-3">
            {f.data.items.map((it) => (
              <div key={it.horizonMin} className="tile p-6">
                <p className="eyebrow">{it.horizonMin}분 뒤 · {hm(it.targetAt)} 출발</p>
                <p className="mt-1 text-xs text-muted">마지막 관측에서 {durMin(it.leadMin)} 뒤 · 평소 값 표본 {it.baselineN}일
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

      <Section eyebrow="지난 기록으로 채점" title="몇 분 뒤를 예측했을 때 평균 몇 분 틀렸나" wide
               desc={["매 정시에 예측했다고 치고, 그 몇 분 뒤의 실제 통행시간과 비교했습니다.",
                 "예측할 때마다 그 전 8주 기록만 써서 앞날의 값이 섞이지 않게 했습니다.",
                 bt?.evalDate ? `채점일 ${bt.evalDate} · 기간 ${bt.period} · ${modelVersionText(bt.modelVersion)}` : null]}>
        {bt && bt.cells.length === 0 && <Empty>아직 채점할 기록이 충분히 쌓이지 않았습니다 (수집 시작 후 하루 이상 필요).</Empty>}
        {bt && bt.cells.length > 0 && (
          <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
            <div className="tile p-6"><MaeChart cells={bt.cells} /></div>
            <div className="tile overflow-x-auto">
              <table className="w-full">
                <caption className="sr-only">예측 방법별 평균 오차 표</caption>
                <thead><tr><th className="th">몇 분 뒤</th><th className="th">예측 방법</th><th className="th text-right">평균 오차</th><th className="th text-right">평균 오차율</th><th className="th text-right">채점 횟수</th></tr></thead>
                <tbody>
                  {bt.cells.map((cl) => (
                    <tr key={cl.horizonMin + cl.model}>
                      <td className="td">{cl.horizonMin}분</td>
                      <td className="td">{MODELS.find((m) => m.key === cl.model)?.name ?? cl.model}</td>
                      <td className="td text-right">{cl.maeSec == null ? DASH : `${num(cl.maeSec / 60)}분`}</td>
                      <td className="td text-right">{pct(cl.mape, 1)}</td>
                      <td className="td text-right">{cl.n}번</td>
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
