import Link from "next/link";
import type { Decision, EnvPoint, Trip } from "@/lib/types";
import { DASH, dur, durMin, hm, MODEL_LABEL, num, pct, pm25Label, signedPct } from "@/lib/format";

function Row({ k, v, sub }: { k: string; v: React.ReactNode; sub?: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-line py-3 last:border-0">
      <span className="text-sm text-muted">{k}</span>
      <span className="text-right text-sm font-medium text-ink tabular">{v}{sub && <span className="ml-1 font-normal text-muted">{sub}</span>}</span>
    </div>
  );
}

export function CarTile({ trip }: { trip: Trip }) {
  const c = trip.car, o = trip.observed;
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-road" aria-hidden />자동차</p>
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-[40px] font-medium leading-none tabular">{c.pending && c.durationSec == null ? "…" : durMin(trip.decision.carTotalMin ?? (c.durationSec ? c.durationSec / 60 : null))}</span>
        <span className="text-sm text-muted">{c.pending && c.durationSec == null ? "경로 조회 중" : "예상"}</span>
      </div>
      <p className="mt-2 text-[13px] text-muted">{trip.from.name} → {trip.to.name} · {hm(trip.departAt)} 출발 · 카카오 경로 예측</p>
      <div className="mt-6">
        <Row k="경로 거리" v={c.distanceM == null ? DASH : `${num(c.distanceM / 1000, 0)}km`} sub={`직선 ${num(trip.distanceKm, 0)}km`} />
        <Row k="출발 시각 기준 소요" v={dur(c.durationSec)} sub="카카오 미래 운행 정보" />
        {o ? (
          <>
            <Row k="고속도로 실측 (수집 중인 길)" v={dur(o.travelSec)} sub={`${o.corridorName} · ${hm(o.slotTs)} 슬롯`} />
            <Row k="같은 요일·시간 중앙값 대비" v={o.vsBaselinePct == null ? "비교 불가" : signedPct(o.vsBaselinePct)}
                 sub={o.vsBaselinePct == null ? "표본 4일 미만" : undefined} />
            <Row k="고속도로 구간 예측" v={dur(o.predictedSec)} sub={`${MODEL_LABEL[o.model] ?? o.model} · 선행 ${durMin(o.leadMin)}`} />
          </>
        ) : (
          <p className="py-3 text-xs leading-relaxed text-muted">고속도로 실측(평소 대비 정체)은 수집 중인 길 8개에서만 보입니다. 이 길은 카카오 경로 예측만 사용합니다.</p>
        )}
      </div>
      {o && <Link href={`/road/${o.corridorId}?dir=${o.direction}`} className="mt-auto pt-6 text-center text-sm font-medium text-ink underline underline-offset-4">도로 분석 보기</Link>}
    </div>
  );
}

export function TrainTile({ trip }: { trip: Trip }) {
  const r = trip.rail;
  const has = r?.dep && r.arr;
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rail" aria-hidden />기차 · 직통</p>
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-[40px] font-medium leading-none tabular">{durMin(trip.decision.trainTotalMin)}</span>
        <span className="text-sm text-muted">예상</span>
      </div>
      {has ? (
        <p className="mt-2 text-[13px] text-muted">
          {r!.dep!.name}역 → {r!.arr!.name}역 · 역까지 {r!.dep!.minutes}분{r!.dep!.estimated && "(추정)"} · 역에서 {r!.arr!.minutes}분(추정)
        </p>
      ) : (
        <p className="mt-2 text-[13px] text-muted">{r?.note ?? (trip.distanceKm < 15 ? "가까운 거리라 기차 비교를 하지 않습니다" : "직통 열차 정보 없음")}</p>
      )}
      <div className="mt-6">
        {has && r!.nextTrains.length === 0 && <p className="py-6 text-center text-sm text-muted">이 시각 이후 열차가 없습니다</p>}
        {r?.nextTrains.map((t, i) => (
          <div key={t.trnNo} className={`flex items-center justify-between border-b border-line py-3 last:border-0 ${i === 0 ? "" : "opacity-80"}`}>
            <div>
              <div className="text-sm font-medium tabular">{t.planDep} → {t.planArr}<span className="ml-2 text-xs font-normal text-muted">{durMin(t.planRideMin)}</span></div>
              <div className="text-xs text-muted">열차 {t.trnNo.replace(/^0+/, "")}</div>
            </div>
            <div className="text-right">
              <div className="text-sm font-medium tabular">{t.onTimeRate30d === null ? DASH : `정시 ${pct(t.onTimeRate30d)}`}</div>
              <div className="text-xs text-muted tabular">평균 지연 {num(t.avgArrDelayMin30d)}분 · {t.samples}회{t.delayEstimated && <span title="중간역 지연은 추정값"> ⚠</span>}</div>
            </div>
          </div>
        ))}
      </div>
      {has && r!.referenceDate && (
        <p className="mt-4 text-xs text-muted">시간표 기준: {r!.referenceDate} ({r!.basis}) — 코레일 API 는 향후 운행계획을 제공하지 않아 최근 같은 요일 운행으로 추정합니다. 근처 역 {r!.pairsTried}개 조합 중 가장 빠른 직통.</p>
      )}
      {has && <Link href={`/rail?dep=${r!.dep!.code}&arr=${r!.arr!.code}`} className="mt-auto pt-6 text-center text-sm font-medium text-ink underline underline-offset-4">철도 분석 보기</Link>}
    </div>
  );
}

export function Evidence({ decision, freshness, caveat, cache, asOf }: {
  decision: Decision; freshness: Record<string, string>; caveat: string; cache: string; asOf: string;
}) {
  const labels: Record<string, string> = { kakao: "카카오 경로", road: "고속도로 실측", rail: "열차 정시성", weather: "단기예보", air: "대기질" };
  return (
    <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
      <div className="tile p-6 sm:p-8">
        <p className="eyebrow">판단 근거 · {decision.rule}</p>
        <p className="mt-2 text-xl font-medium">{decision.summary}</p>
        <ol className="mt-5 space-y-3">
          {decision.reasons.map((r, i) => (
            <li key={i} className="flex gap-3 text-sm text-ink2">
              <span className="mt-0.5 flex h-5 w-5 flex-none items-center justify-center rounded-full bg-cloud text-[11px] font-medium text-ink">{i + 1}</span>
              <span>{r}</span>
            </li>
          ))}
        </ol>
        {decision.warnings.length > 0 && (
          <ul className="mt-5 space-y-2">
            {decision.warnings.map((w) => (
              <li key={w} className="flex items-center gap-2 rounded bg-[#fff7e6] px-3 py-2 text-sm text-ink2">
                <span className="text-warn" aria-hidden>▲</span><span className="sr-only">경고: </span>{w}
              </li>
            ))}
          </ul>
        )}
        <p className="mt-5 text-xs leading-relaxed text-muted">{caveat}</p>
      </div>
      <div className="tile p-6 sm:p-8">
        <p className="eyebrow">데이터 시각</p>
        <dl className="mt-3">
          {Object.entries(labels).filter(([k]) => freshness[k]).map(([k, label]) => (
            <div key={k} className="flex justify-between gap-3 border-b border-line py-2.5 text-sm last:border-0">
              <dt className="text-muted">{label}</dt><dd className="text-right text-ink2">{freshness[k]}</dd>
            </div>
          ))}
        </dl>
        <p className="mt-4 text-xs text-muted">응답 캐시 {cache} · {hm(asOf)} 계산</p>
      </div>
    </div>
  );
}

export function EnvRow({ env }: { env: Record<"origin" | "dest", EnvPoint> }) {
  return (
    <div className="grid gap-6 sm:grid-cols-2">
      {(["origin", "dest"] as const).map((role) => {
        const e = env[role];
        return (
          <div key={role} className="tile p-6 sm:p-8">
            <p className="eyebrow">{role === "origin" ? "어디서" : "어디로"} · {e?.name ?? DASH}</p>
            <div className="mt-4 grid grid-cols-3 gap-4 text-center">
              <div><div className="text-2xl font-medium tabular">{e?.tmp ?? DASH}{e?.tmp != null && "°"}</div><div className="mt-1 text-xs text-muted">{e?.sky ?? "기온"}</div></div>
              <div><div className="text-2xl font-medium tabular">{e?.pop ?? DASH}{e?.pop != null && "%"}</div><div className="mt-1 text-xs text-muted">강수확률 · {e?.pty ?? DASH}</div></div>
              <div><div className="text-2xl font-medium tabular">{e?.pm25 ?? DASH}</div><div className="mt-1 text-xs text-muted">초미세먼지 · {pm25Label(e?.pm25Grade)}</div></div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
