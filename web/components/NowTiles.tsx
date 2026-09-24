import Link from "next/link";
import type { NowCard } from "@/lib/types";
import { DASH, dur, durMin, hm, MODEL_LABEL, num, pct, pm25Label, signedPct } from "@/lib/format";

function Row({ k, v, sub }: { k: string; v: React.ReactNode; sub?: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-line py-3 last:border-0">
      <span className="text-sm text-muted">{k}</span>
      <span className="text-right text-sm font-medium text-ink tabular">{v}{sub && <span className="ml-1 font-normal text-muted">{sub}</span>}</span>
    </div>
  );
}

export function RoadTile({ card }: { card: NowCard }) {
  const r = card.road;
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-road" aria-hidden />자동차 · 고속도로</p>
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-[40px] font-medium leading-none tabular">{durMin(card.decision.carTotalMin)}</span>
        <span className="text-sm text-muted">예상</span>
      </div>
      <p className="mt-2 text-[13px] text-muted">{r.from} → {r.to} · {num(r.distanceKm, 0)}km · {hm(card.departAt)} 출발</p>
      <div className="mt-6">
        <Row k="예측 (출발 시점)" v={dur(r.predictedSec)} sub={r.model ? `${MODEL_LABEL[r.model] ?? r.model} · 선행 ${durMin(r.leadMin)}` : undefined} />
        <Row k="최근 관측" v={dur(r.travelSec)} sub={r.slotTs ? `${hm(r.slotTs)} 슬롯` : undefined} />
        <Row k="같은 요일·시간 중앙값" v={dur(r.baselineP50Sec)} sub={r.vsBaselinePct !== null ? signedPct(r.vsBaselinePct) : undefined} />
        <Row k="카카오 미래 운행 정보" v={r.kakao ? dur(r.kakao.durationSec) : DASH} sub={r.kakao ? "교차 확인" : "없음"} />
        <Row k="구간 실측 비율" v={r.coverage === null ? DASH : pct(r.coverage)} sub={r.quality === "FILLED" ? "일부 보정" : undefined} />
      </div>
      <div className="mt-auto pt-6 grid grid-cols-3 gap-2 text-center">
        {r.forecast.map((f) => (
          <div key={f.model} className="rounded bg-cloud py-2">
            <div className="text-[11px] text-muted">{MODEL_LABEL[f.model] ?? f.model}</div>
            <div className="text-sm font-medium tabular">{dur(f.travelSec)}</div>
          </div>
        ))}
      </div>
      <Link href={`/road/${card.corridorId}?dir=${card.direction}`} className="mt-6 text-center text-sm font-medium text-ink underline underline-offset-4">도로 분석 보기</Link>
    </div>
  );
}

export function RailTile({ card }: { card: NowCard }) {
  const r = card.rail;
  const first = r.nextTrains[0];
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rail" aria-hidden />기차 · 코레일</p>
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-[40px] font-medium leading-none tabular">{durMin(card.decision.trainTotalMin)}</span>
        <span className="text-sm text-muted">예상</span>
      </div>
      <p className="mt-2 text-[13px] text-muted">{r.depStation}역 → {r.arrStation}역 · 역 접근 {card.accessMin}분 포함</p>
      <div className="mt-6">
        {r.nextTrains.length === 0 && <p className="py-6 text-center text-sm text-muted">이 시각 이후 열차가 없습니다 ({r.basis})</p>}
        {r.nextTrains.map((t, i) => (
          <div key={t.trnNo} className={`flex items-center justify-between border-b border-line py-3 last:border-0 ${i === 0 ? "" : "opacity-80"}`}>
            <div>
              <div className="text-sm font-medium tabular">{t.planDep} → {t.planArr}
                <span className="ml-2 text-xs font-normal text-muted">{durMin(t.planRideMin)}</span></div>
              <div className="text-xs text-muted">열차 {t.trnNo.replace(/^0+/, "")}</div>
            </div>
            <div className="text-right">
              <div className="text-sm font-medium tabular">{t.onTimeRate30d === null ? DASH : `정시 ${pct(t.onTimeRate30d)}`}</div>
              <div className="text-xs text-muted tabular">
                평균 지연 {num(t.avgArrDelayMin30d)}분 · {t.samples}회{t.delayEstimated && <span title="중간역 지연은 추정값"> ⚠</span>}
              </div>
            </div>
          </div>
        ))}
      </div>
      <p className="mt-4 text-xs text-muted">
        {first ? `시간표 기준: ${r.referenceDate} (${r.basis}) — 코레일 API 는 향후 운행계획을 제공하지 않아 최근 같은 요일 운행으로 추정합니다.` : ""}
      </p>
      <Link href={`/rail/${card.corridorId}?dir=${card.direction}`} className="mt-auto pt-6 text-center text-sm font-medium text-ink underline underline-offset-4">철도 분석 보기</Link>
    </div>
  );
}

export function Evidence({ card }: { card: NowCard }) {
  const d = card.decision;
  return (
    <div className="grid gap-6 lg:grid-cols-[1.4fr_1fr]">
      <div className="tile p-6 sm:p-8">
        <p className="eyebrow">판단 근거 · {d.rule}</p>
        <p className="mt-2 text-xl font-medium">{d.summary}</p>
        <ol className="mt-5 space-y-3">
          {d.reasons.map((r, i) => (
            <li key={i} className="flex gap-3 text-sm text-ink2">
              <span className="mt-0.5 flex h-5 w-5 flex-none items-center justify-center rounded-full bg-cloud text-[11px] font-medium text-ink">{i + 1}</span>
              <span>{r}</span>
            </li>
          ))}
        </ol>
        {d.warnings.length > 0 && (
          <ul className="mt-5 space-y-2">
            {d.warnings.map((w) => (
              <li key={w} className="flex items-center gap-2 rounded bg-[#fff7e6] px-3 py-2 text-sm text-ink2">
                <span className="text-warn" aria-hidden>▲</span><span className="sr-only">경고: </span>{w}
              </li>
            ))}
          </ul>
        )}
        <p className="mt-5 text-xs leading-relaxed text-muted">{card.caveat}</p>
      </div>
      <div className="tile p-6 sm:p-8">
        <p className="eyebrow">데이터 시각</p>
        <dl className="mt-3">
          {Object.entries({ road: "도로 통행시간", rail: "열차 정시성", weather: "단기예보", air: "대기질", kakao: "카카오 경로" }).map(([k, label]) => (
            <div key={k} className="flex justify-between gap-3 border-b border-line py-2.5 text-sm last:border-0">
              <dt className="text-muted">{label}</dt><dd className="text-right text-ink2">{card.freshness[k] ?? DASH}</dd>
            </div>
          ))}
        </dl>
        <p className="mt-4 text-xs text-muted">응답 캐시 {card.cache} · {hm(card.asOf)} 계산 {card.status === "STALE_DATA" && "· ⚠ 도로 데이터가 오래됨"}</p>
      </div>
    </div>
  );
}

export function EnvRow({ card }: { card: NowCard }) {
  return (
    <div className="grid gap-6 sm:grid-cols-2">
      {(["origin", "dest"] as const).map((role) => {
        const e = card.env[role];
        return (
          <div key={role} className="tile p-6 sm:p-8">
            <p className="eyebrow">{role === "origin" ? "출발" : "도착"} · {e?.name ?? DASH}</p>
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
