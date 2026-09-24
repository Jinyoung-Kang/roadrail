import Link from "next/link";
import type { Decision, EnvPoint, Incident, Journey, NextSubway, Trip } from "@/lib/types";
import { DASH, dur, durMin, hm, mdhm, MODEL_LABEL, num, pct, pm25Label, signedPct } from "@/lib/format";
import { encodePlace } from "@/lib/places";
import { TrainName } from "@/components/ui";

/** 분 → ISO 시각 */
const plusMin = (iso: string, min: number) => new Date(new Date(iso).getTime() + min * 60_000).toISOString();

/** 큰 소요 시간 + 도착 예정 시각 */
function Headline({ total, pending, arrive, note }: { total: number | null; pending?: boolean; arrive: string | null; note: string }) {
  return (
    <div className="mt-4 flex items-end justify-between gap-4">
      <div>
        <p className="text-[40px] font-medium leading-none tabular sm:text-[44px]">{pending ? "…" : durMin(total)}</p>
        <p className="mt-2 text-[13px] text-muted">{note}</p>
      </div>
      <div className="shrink-0 text-right">
        <p className="text-[11px] text-muted">도착 예정</p>
        <p className="text-2xl font-medium leading-tight tabular">{arrive ? hm(arrive) : DASH}</p>
      </div>
    </div>
  );
}

function Stats({ items }: { items: { k: string; v: React.ReactNode; sub?: string }[] }) {
  return (
    <dl className="mt-6 grid grid-cols-3 divide-x divide-line rounded bg-mist py-3">
      {items.map((it) => (
        <div key={it.k} className="px-3 text-center">
          <dt className="text-[11px] text-muted">{it.k}</dt>
          <dd className="mt-1 text-[15px] font-medium tabular text-ink">{it.v}</dd>
          {it.sub && <dd className="text-[11px] text-muted">{it.sub}</dd>}
        </div>
      ))}
    </dl>
  );
}

export interface Segment { label: string; min: number; className: string }

/**
 * 시간 구성 막대 — 두 카드가 같은 축(max)을 써서 막대 길이로 바로 비교된다.
 * 색만으로 구분하지 않도록 아래에 항목·분을 글자로 함께 적는다.
 */
function TimeBar({ segments, max }: { segments: Segment[]; max: number }) {
  const seg = segments.filter((x) => x.min > 0);
  const total = seg.reduce((t, x) => t + x.min, 0);
  if (!total || !max) return null;
  return (
    <div className="mt-6">
      <div className="flex h-3 w-full gap-[2px]" role="img"
           aria-label={`시간 구성: ${seg.map((x) => `${x.label} ${Math.round(x.min)}분`).join(", ")}`}>
        {seg.map((x) => (
          <span key={x.label} className={`h-full first:rounded-l last:rounded-r ${x.className}`}
                style={{ width: `${(x.min / max) * 100}%` }} title={`${x.label} ${Math.round(x.min)}분`} />
        ))}
      </div>
      <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[12px] text-ink2">
        {seg.map((x) => (
          <li key={x.label} className="flex items-center gap-1.5">
            <span className={`h-2 w-2 rounded-sm ${x.className}`} aria-hidden />{x.label} <span className="tabular text-muted">{durMin(Math.round(x.min))}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** 두 카드 공통 축: 자동차 · 기차 총 소요 중 큰 값 */
export const barMax = (trip: Trip) => Math.max(trip.decision.carTotalMin ?? 0, trip.decision.trainTotalMin ?? 0);

export function CarTile({ trip }: { trip: Trip }) {
  const c = trip.car, o = trip.observed, d = trip.decision;
  const pending = c.pending && c.durationSec == null;
  const total = d.carTotalMin ?? (c.durationSec ? Math.round(c.durationSec / 60) : null);
  const drive = c.durationSec ? c.durationSec / 60 : null;
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-road" aria-hidden />자동차 · 도로</p>
      <Headline total={total} pending={pending} arrive={total == null ? null : plusMin(trip.departAt, total)}
                note={pending ? "카카오 경로 조회 중" : `${hm(trip.departAt)} 출발 · 카카오 미래 운행 예측`} />
      <Stats items={[
        { k: "경로 거리", v: c.distanceM == null ? DASH : `${num(c.distanceM / 1000, 0)}km` },
        { k: "평균 속도", v: c.distanceM && c.durationSec ? `${num(c.distanceM / 1000 / (c.durationSec / 3600), 0)}km/h` : DASH },
        { k: "직선 거리", v: `${num(trip.distanceKm, 0)}km` },
      ]} />
      {drive != null && total != null && (
        <TimeBar max={barMax(trip)} segments={[{ label: "운전", min: drive, className: "bg-road" }]} />
      )}
      {o ? (
        <div className="mt-6 rounded ring-1 ring-line">
          <p className="border-b border-line px-4 py-2.5 text-[12px] font-medium text-ink2">
            고속도로 실측 · {o.corridorName} <span className="font-normal text-muted">{hm(o.slotTs)} 기준</span>
          </p>
          <dl className="grid grid-cols-3 divide-x divide-line py-3">
            <div className="px-3 text-center"><dt className="text-[11px] text-muted">지금 구간</dt><dd className="mt-1 text-[15px] font-medium tabular">{dur(o.travelSec)}</dd></div>
            <div className="px-3 text-center"><dt className="text-[11px] text-muted">평소 대비</dt>
              <dd className="mt-1 text-[15px] font-medium tabular">{o.vsBaselinePct == null ? DASH : signedPct(o.vsBaselinePct)}</dd>
              {o.vsBaselinePct == null && <dd className="text-[11px] text-muted">표본 4일 미만</dd>}</div>
            <div className="px-3 text-center"><dt className="text-[11px] text-muted">예측</dt><dd className="mt-1 text-[15px] font-medium tabular">{dur(o.predictedSec)}</dd>
              <dd className="text-[11px] text-muted">{MODEL_LABEL[o.model] ?? o.model} · {durMin(o.leadMin)} 뒤</dd></div>
          </dl>
        </div>
      ) : (
        <p className="mt-6 text-xs leading-relaxed text-muted">평소 대비 정체(고속도로 실측)는 수집 중인 길 8개에서만 보입니다. 이 경로는 카카오 예측만 사용합니다.</p>
      )}
      <div className="mt-auto flex flex-wrap justify-center gap-x-5 pt-6">
        <Link href={`/road?from=${encodeURIComponent(encodePlace(trip.from))}&to=${encodeURIComponent(encodePlace(trip.to))}`}
              className="text-sm font-medium text-ink underline underline-offset-4">경로 분석 보기</Link>
        {o && <Link href={`/road/${o.corridorId}?dir=${o.direction}`} className="text-sm font-medium text-ink underline underline-offset-4">고속도로 실측 보기</Link>}
      </div>
    </div>
  );
}

const MODE: Record<string, string> = { WALK: "도보 추정", CAR: "차량 · 카카오 실제 경로", INPUT: "입력값" };

function Dot({ tone }: { tone: "ink" | "rail" | "muted" }) {
  const c = tone === "rail" ? "bg-rail" : tone === "ink" ? "bg-ink" : "bg-white ring-faint";
  return <span className={`relative z-10 mt-[5px] h-2.5 w-2.5 flex-none rounded-full ring-2 ${tone === "muted" ? "" : "ring-white"} ${c}`} aria-hidden />;
}

function Step({ time, tone, children, last = false }: { time: string; tone: "ink" | "rail" | "muted"; children: React.ReactNode; last?: boolean }) {
  return (
    <li className="grid grid-cols-[44px_10px_1fr] gap-x-3">
      <span className="pt-[1px] text-right text-[13px] font-medium tabular text-ink">{time}</span>
      <span className="relative flex justify-center">
        {!last && <span className="absolute left-1/2 top-3 bottom-[-4px] w-px -translate-x-1/2 bg-line" aria-hidden />}
        <Dot tone={tone} />
      </span>
      <div className={`min-w-0 text-sm ${last ? "" : "pb-4"}`}>{children}</div>
    </li>
  );
}

/** 출발지 → 역 → 열차(환승) → 역 → 도착지 — 왼쪽은 시각 */
export function JourneyTimeline({ j, trip }: { j: Journey; trip: Trip }) {
  const start = trip.departAt;
  const end = plusMin(trip.departAt, j.totalMin);
  const rows: React.ReactNode[] = [];
  rows.push(
    <Step key="o" time={hm(start)} tone="ink">
      <p className="font-medium">{trip.from.name} 출발</p>
      <p className="text-xs text-muted">{j.access.stationName}역까지 {j.access.minutes}분 · {MODE[j.access.mode]}{j.access.distanceM ? ` ${num(j.access.distanceM / 1000)}km` : ""}
        {j.waitMin > 0 && ` · 역에서 ${j.waitMin}분 대기`}</p>
    </Step>);
  j.legs.forEach((l, i) => {
    const prev = j.legs[i - 1];
    const gap = prev ? Math.round((new Date(l.dep).getTime() - new Date(prev.arr).getTime()) / 60000) : 0;
    rows.push(
      <Step key={`d${i}`} time={hm(l.dep)} tone="rail">
        <p className="font-medium">{l.fromName}역 {i === 0 ? "승차" : "갈아타기"}{i > 0 && <span className="font-normal text-muted"> · {gap}분 대기</span>}</p>
        <div className="mt-1.5 rounded bg-mist px-3 py-2">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <TrainName trnNo={l.trnNo} meta={l.meta} grade={l.grade} />
          </div>
          <p className="mt-1 text-xs text-muted tabular">
            {durMin(l.rideMin)} 탑승 · {l.onTimeRate30d == null ? "정시율 —" : `정시 ${pct(l.onTimeRate30d)}`} · 평균 지연 {num(l.avgArrDelayMin30d)}분
            {l.samples ? ` · 최근 30일 ${l.samples}회` : ""}
          </p>
        </div>
      </Step>);
    rows.push(
      <Step key={`a${i}`} time={hm(l.arr)} tone={i < j.legs.length - 1 ? "muted" : "rail"}>
        <p className="font-medium">{l.toName}역 하차{i < j.legs.length - 1 && <span className="font-normal text-muted"> (환승)</span>}</p>
        {i === j.legs.length - 1 && (
          <p className="text-xs text-muted">{j.expectedDelayMin && j.expectedDelayMin > 0 ? `평균 ${num(j.expectedDelayMin)}분 늦게 도착 · ` : ""}
            목적지까지 {j.egress.minutes}분 · {MODE[j.egress.mode]}{j.egress.distanceM ? ` ${num(j.egress.distanceM / 1000)}km` : ""}</p>
        )}
      </Step>);
  });
  rows.push(
    <Step key="e" time={hm(end)} tone="ink" last>
      <p className="font-medium">{trip.to.name} 도착 <span className="font-normal text-muted">(지연 반영 예상)</span></p>
    </Step>);
  return <ol>{rows}</ol>;
}

export function SubwayList({ title, items }: { title: string; items: NextSubway[] }) {
  if (!items.length) return null;
  return (
    <div>
      <p className="text-xs font-medium text-ink2">{title}</p>
      <ul className="mt-1.5 space-y-1.5">
        {items.slice(0, 6).map((s) => (
          <li key={s.line + s.toward} className="flex justify-between gap-3 text-xs text-muted">
            <span><span className="rounded bg-cloud px-1.5 py-0.5 text-ink2">{s.line}</span> {s.toward}</span><span className="tabular">{s.times.join(" · ")}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Fold({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <details className="group border-t border-line">
      <summary className="flex cursor-pointer list-none items-center justify-between py-3 text-sm font-medium text-ink hover:text-ink2">
        {title}<span className="text-xs text-muted transition group-open:rotate-180" aria-hidden>▾</span>
      </summary>
      <div className="pb-4">{children}</div>
    </details>
  );
}

export function TrainTile({ trip }: { trip: Trip }) {
  const r = trip.rail;
  const j = r?.journeys[0];
  const ride = j ? j.legs.reduce((t, l) => t + l.rideMin, 0) : 0;
  const transferWait = j ? Math.max((new Date(j.arriveAt).getTime() - new Date(j.departAt).getTime()) / 60000 - ride, 0) : 0;
  const first = j?.legs[0], lastLeg = j?.legs[j.legs.length - 1];
  return (
    <div className="tile flex flex-col p-6 sm:p-8">
      <p className="eyebrow flex items-center gap-2"><span className="h-2 w-2 rounded-full bg-rail" aria-hidden />기차 · 코레일 (환승 포함)</p>
      <Headline total={trip.decision.trainTotalMin} arrive={j ? plusMin(trip.departAt, j.totalMin) : null}
                note={j ? `${j.transfers === 0 ? "직통" : `환승 ${j.transfers}회`} · ${hm(j.departAt)} 열차 · 역 오가는 시간 포함`
                        : r?.note ?? (trip.distanceKm < 15 ? "가까운 거리라 기차 비교를 하지 않습니다" : "기차 정보 없음")} />
      {j && first && lastLeg && (
        <>
          <Stats items={[
            { k: "타는 역", v: `${first.fromName}`, sub: `${j.access.minutes}분 거리` },
            { k: "내리는 역", v: `${lastLeg.toName}`, sub: `${j.egress.minutes}분 거리` },
            { k: "정시율 (30일)", v: lastLeg.onTimeRate30d == null ? DASH : pct(lastLeg.onTimeRate30d),
              sub: j.legs.length > 1 ? "마지막 열차" : lastLeg.samples ? `${lastLeg.samples}회 운행` : undefined },
          ]} />
          <TimeBar max={barMax(trip)} segments={[
            { label: "역까지", min: j.access.minutes, className: "bg-ink2" },
            { label: "대기", min: j.waitMin, className: "bg-line" },
            { label: "탑승", min: ride, className: "bg-rail" },
            { label: "환승 대기", min: transferWait, className: "bg-rail/35" },
            { label: "예상 지연", min: Math.max(j.expectedDelayMin ?? 0, 0), className: "bg-warn" },
            { label: "역에서", min: j.egress.minutes, className: "bg-faint" },
          ]} />
          <div className="mt-6"><JourneyTimeline j={j} trip={trip} /></div>
        </>
      )}
      {r && (r.journeys.length > 1 || r.subwayAtArrival.length > 0 || r.subwayAtDeparture.length > 0) && (
        <div className="mt-5">
          {r.journeys.length > 1 && (
            <Fold title={`다른 여정 ${r.journeys.length - 1}개`}>
              <ul className="space-y-2">
                {r.journeys.slice(1).map((x) => (
                  <li key={x.departAt + x.legs[0].trnNo} className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="min-w-0">
                      <span className="font-medium tabular">{hm(x.departAt)} → {hm(x.arriveAt)}</span>
                      <span className="ml-2 text-xs text-muted">{x.legs[0].fromName}→{x.legs[x.legs.length - 1].toName}
                        {x.transfers ? ` · ${x.legs.slice(1).map((l) => l.fromName).join("·")} 환승` : " · 직통"}
                        {x.legs[0].grade ? ` · ${x.legs[0].grade}` : ""}
</span>
                    </span>
                    <span className="shrink-0 text-xs text-ink2 tabular">총 {durMin(x.totalMin)}</span>
                  </li>
                ))}
              </ul>
            </Fold>
          )}
          {(r.subwayAtArrival.length > 0 || r.subwayAtDeparture.length > 0) && (
            <Fold title="역에서 갈아탈 지하철 (TAGO 시간표)">
              <div className="space-y-4">
                <SubwayList title={`${lastLeg?.toName ?? ""}역 도착 후`} items={r.subwayAtArrival} />
                <SubwayList title={`${first?.fromName ?? ""}역 출발 전`} items={r.subwayAtDeparture} />
              </div>
            </Fold>
          )}
        </div>
      )}
      {j && r?.referenceDate && (
        <p className="mt-3 text-xs leading-relaxed text-muted">시간표 기준 {r.referenceDate} ({r.basis}) — 앞으로의 시간표는 공개 데이터에 없어 최근 같은 요일의 실제 시간표(코레일 운행계획 · TAGO)를 씁니다{j.legs.some((l) => !l.timetable) && " · ⚠ 일부 구간은 시간표를 받지 못해 보간한 시각"} · 근처 역 {r.originCandidates}×{r.destCandidates}곳 조합 · 승차 여유 {r.boardingBufferMin}분 · 최소 환승 {r.transferMin}분 · {r.note}</p>
      )}
      {j && (
        <div className="mt-auto flex flex-wrap justify-center gap-x-5 pt-6">
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

/**
 * 돌발 안내 한 건 — 도로공사 문자 안내 그대로(유형 · 노선 · 방향 · 안내 구간 · 본문 · 발송 시각).
 * 위치는 응답에 좌표가 있을 때만 '지도에서 보기' — 없으면 '위치 정보 없음'(추정하지 않음).
 */
export function IncidentItem({ i, onLocate }: { i: Incident; onLocate?: (i: Incident) => void }) {
  const located = i.lat != null && i.lon != null;
  return (
    <li className="rounded bg-white px-4 py-3 ring-1 ring-black/5">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
        <span className="rounded bg-[#fff1cc] px-1.5 py-0.5 text-[11px] font-medium text-ink">{i.typeName || "돌발"}</span>
        <span className="font-medium text-ink">{i.routeName}</span>
        {i.direction && <span className="text-ink2">{i.direction}</span>}
        {i.process && <span className="text-xs text-muted">· {i.process}</span>}
      </div>
      {i.pointName && <p className="mt-1 text-xs text-ink2">구간 {i.pointName}</p>}
      <p className="mt-1 text-sm leading-relaxed text-ink2">{i.content}</p>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
        <span>{mdhm(i.sentAt)} 발송{i.routeKm != null ? ` · 안내 지점이 자동차 경로에서 ${num(i.routeKm)}km` : located ? "" : " · 위치 정보 없음 (수집 중인 길과 같은 노선)"}</span>
        {located && onLocate && (
          <button type="button" onClick={() => onLocate(i)} className="font-medium text-accent hover:underline">지도에서 보기 ↓</button>
        )}
      </div>
    </li>
  );
}

export function Evidence({ decision, freshness, caveat, cache, asOf, incidents = [], onLocate }: {
  decision: Decision; freshness: Record<string, string>; caveat: string; cache: string; asOf: string;
  incidents?: Incident[]; onLocate?: (i: Incident) => void;
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
              <li key={w} className="rounded bg-[#fff7e6] px-3 py-2 text-sm text-ink2">
                <p className="flex items-center gap-2"><span className="text-warn" aria-hidden>▲</span><span className="sr-only">경고: </span>{w}</p>
                {w.includes("돌발") && incidents.length > 0 && (
                  <ul className="mb-1 mt-2 space-y-2" aria-label="돌발 안내 목록">
                    {incidents.map((i) => <IncidentItem key={i.sentAt + i.content} i={i} onLocate={onLocate} />)}
                  </ul>
                )}
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
            <p className="eyebrow">{role === "origin" ? "출발지" : "도착지"} · {e?.name ?? DASH}</p>
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
