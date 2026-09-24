import Link from "next/link";
import type { Decision, EnvPoint, Journey, NextSubway, Trip } from "@/lib/types";
import { DASH, dur, durMin, hm, MODEL_LABEL, num, pct, pm25Label, signedPct } from "@/lib/format";
import { encodePlace } from "@/lib/places";

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
      <div className="mt-auto flex flex-wrap justify-center gap-x-4 pt-6">
        <Link href={`/road?from=${encodeURIComponent(encodePlace(trip.from))}&to=${encodeURIComponent(encodePlace(trip.to))}`}
              className="text-sm font-medium text-ink underline underline-offset-4">경로 분석 보기</Link>
        {o && <Link href={`/road/${o.corridorId}?dir=${o.direction}`} className="text-sm font-medium text-ink underline underline-offset-4">고속도로 실측 보기</Link>}
      </div>
    </div>
  );
}

const MODE: Record<string, string> = { WALK: "도보 · 추정", CAR: "차량 · 카카오 실제 경로", INPUT: "입력값", ESTIMATE: "차량 · 직선거리 추정" };

function Dot({ tone }: { tone: "ink" | "rail" | "muted" }) {
  const c = tone === "rail" ? "bg-rail" : tone === "ink" ? "bg-ink" : "bg-faint";
  return <span className={`relative z-10 mt-1.5 h-2.5 w-2.5 flex-none rounded-full ring-2 ring-white ${c}`} aria-hidden />;
}

/** 어디서 → 역 → 열차(환승) → 역 → 어디로 */
export function JourneyTimeline({ j }: { j: Journey }) {
  const rows: React.ReactNode[] = [];
  rows.push(
    <li key="acc" className="flex gap-3"><Dot tone="ink" />
      <div className="pb-4 text-sm"><span className="font-medium">역까지 {j.access.minutes}분</span>
        <span className="text-muted"> · {j.access.stationName}역 · {MODE[j.access.mode]}{j.access.distanceM ? ` ${num(j.access.distanceM / 1000)}km` : ""}</span></div></li>);
  j.legs.forEach((l, i) => {
    if (i > 0) {
      const gap = Math.round((new Date(l.dep).getTime() - new Date(j.legs[i - 1].arr).getTime()) / 60000);
      rows.push(<li key={`x${i}`} className="flex gap-3"><Dot tone="muted" />
        <div className="pb-4 text-sm"><span className="font-medium">{l.fromName} 환승</span><span className="text-muted"> · {gap}분 대기</span></div></li>);
    }
    rows.push(
      <li key={`l${i}`} className="flex gap-3"><Dot tone="rail" />
        <div className="flex-1 pb-4 text-sm">
          <div className="flex flex-wrap items-baseline justify-between gap-x-3">
            <span className="font-medium tabular">{hm(l.dep)} {l.fromName} → {hm(l.arr)} {l.toName}</span>
            <span className="text-xs text-muted tabular">{l.onTimeRate30d == null ? "정시율 —" : `정시 ${pct(l.onTimeRate30d)}`} · 평균 지연 {num(l.avgArrDelayMin30d)}분{l.delayEstimated && " ⚠"}</span>
          </div>
          <div className="text-xs text-muted">열차 {l.trnNo.replace(/^0+/, "")} · {durMin(l.rideMin)}{l.samples ? ` · 최근 30일 ${l.samples}회` : ""}</div>
        </div></li>);
  });
  rows.push(
    <li key="eg" className="flex gap-3"><Dot tone="ink" />
      <div className="text-sm"><span className="font-medium">역에서 {j.egress.minutes}분</span>
        <span className="text-muted"> · {j.egress.stationName}역 → 목적지 · {MODE[j.egress.mode]}{j.egress.distanceM ? ` ${num(j.egress.distanceM / 1000)}km` : ""}</span></div></li>);
  return (
    <ol className="relative">
      <span className="absolute left-[4.5px] top-2 bottom-2 w-px bg-line" aria-hidden />
      {rows}
    </ol>
  );
}

function SubwayList({ title, items }: { title: string; items: NextSubway[] }) {
  if (!items.length) return null;
  return (
    <div className="mt-4">
      <p className="text-xs font-medium text-ink2">{title}</p>
      <ul className="mt-1 space-y-1">
        {items.slice(0, 6).map((s) => (
          <li key={s.line + s.toward} className="flex justify-between gap-3 text-xs text-muted">
            <span><span className="rounded bg-cloud px-1.5 py-0.5 text-ink2">{s.line}</span> {s.toward}</span><span className="tabular">{s.times.join(" · ")}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function TrainTile({ trip }: { trip: Trip }) {
  const r = trip.rail;
  const j = r?.journeys[0];
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rail" aria-hidden />기차 · 코레일 (환승 포함)</p>
      <div className="mt-3 flex items-baseline gap-2">
        <span className="text-[40px] font-medium leading-none tabular">{durMin(trip.decision.trainTotalMin)}</span>
        <span className="text-sm text-muted">예상</span>
      </div>
      {j ? (
        <p className="mt-2 text-[13px] text-muted">
          {hm(j.departAt)} {j.legs[0].fromName}역 출발 → {hm(j.arriveAt)} {j.legs[j.legs.length - 1].toName}역 도착 · {j.transfers === 0 ? "직통" : `환승 ${j.transfers}회`}
        </p>
      ) : (
        <p className="mt-2 text-[13px] text-muted">{r?.note ?? (trip.distanceKm < 15 ? "가까운 거리라 기차 비교를 하지 않습니다" : "기차 정보 없음")}</p>
      )}
      {j && <div className="mt-6"><JourneyTimeline j={j} /></div>}
      {r && r.journeys.length > 1 && (
        <div className="mt-5 border-t border-line pt-4">
          <p className="text-xs font-medium text-ink2">다른 여정</p>
          <ul className="mt-1 space-y-1">
            {r.journeys.slice(1).map((x) => (
              <li key={x.departAt + x.legs[0].trnNo} className="flex justify-between gap-3 text-xs text-muted tabular">
                <span>{hm(x.departAt)} {x.legs[0].fromName} → {hm(x.arriveAt)} {x.legs[x.legs.length - 1].toName}{x.transfers ? ` · ${x.legs.slice(1).map((l) => l.fromName).join("·")} 환승` : " · 직통"}</span>
                <span>총 {durMin(x.totalMin)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {r && <SubwayList title={`${j?.legs[j.legs.length - 1].toName ?? ""}역에서 갈아탈 지하철 (TAGO 시간표)`} items={r.subwayAtArrival} />}
      {r && <SubwayList title={`${j?.legs[0].fromName ?? ""}역 지하철`} items={r.subwayAtDeparture} />}
      {j && r?.referenceDate && (
        <p className="mt-4 text-xs text-muted">시간표 기준: {r.referenceDate} ({r.basis}) · 근처 역 {r.originCandidates}×{r.destCandidates}곳 조합 · 승차 여유 {r.boardingBufferMin}분 · 최소 환승 {r.transferMin}분. {r.note}</p>
      )}
      {j && (
        <div className="mt-auto flex flex-wrap justify-center gap-x-4 pt-6">
          {j.legs.map((l) => (
            <Link key={l.trnNo + l.fromCode} href={`/rail?dep=${l.fromCode}&arr=${l.toCode}`} className="text-sm font-medium text-ink underline underline-offset-4">
              {l.fromName}→{l.toName} 철도 분석
            </Link>
          ))}
        </div>
      )}
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
