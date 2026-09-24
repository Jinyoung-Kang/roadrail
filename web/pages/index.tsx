import Link from "next/link";
import { useRouter } from "next/router";
import { useMemo } from "react";
import Layout from "@/components/Layout";
import RouteMap from "@/components/RouteMap";
import { EnvRow, Evidence, RailTile, RoadTile } from "@/components/NowTiles";
import { Empty, ErrorBox, Loading, Section, Segmented, Select, Spec, SpecStrip } from "@/components/ui";
import { qs, useApi } from "@/lib/api";
import { DASH, DIR_LABEL, durParts, hm, mdhm, num } from "@/lib/format";
import type { Dir, NowCard } from "@/lib/types";
import { useCorridors } from "@/lib/useCorridors";

const DEPART = [0, 30, 60, 120, 180].map((v) => ({ value: v, label: v === 0 ? "지금" : `+${v >= 60 ? `${v / 60}시간` : `${v}분`}` }));
const ACCESS = [10, 20, 30, 45].map((v) => ({ value: String(v), label: `역까지 ${v}분` }));

export default function Home() {
  const router = useRouter();
  const q = router.query;
  const cid = (q.c as string) || "SEL-DJN";
  const dir = ((q.dir as string) === "UP" ? "UP" : "DN") as Dir;
  const departIn = Number(q.t ?? 0) || 0;
  const accessMin = Number(q.a ?? 20) || 20;
  const set = (p: Record<string, string | number>) =>
    router.replace({ pathname: "/", query: { c: cid, dir, t: departIn, a: accessMin, ...p } }, undefined, { shallow: true, scroll: false });

  const corridors = useCorridors();
  const corridor = useMemo(() => corridors.data?.find((c) => c.id === cid) ?? null, [corridors.data, cid]);
  const now = useApi<NowCard>(router.isReady ? `/api/v1/corridors/${cid}/now?${qs({ dir, departIn, accessMin })}` : null, 60_000);
  const card = now.data?.corridorId === cid && now.data.direction === dir ? now.data : null;
  const d = card?.decision;
  const car = durParts(d?.carTotalMin), train = durParts(d?.trainTotalMin);
  const title = corridor ? (dir === "DN" ? `${corridor.originCity} → ${corridor.destCity}` : `${corridor.destCity} → ${corridor.originCity}`) : " ";

  return (
    <Layout title="지금 차로 갈까, 기차로 갈까" overlay>
      {/* ---------- 히어로: 지도 배경 + 제목 + 스펙 + 두 버튼 (테슬라 모델 페이지 구성) */}
      <section className="relative h-[100svh] min-h-[680px] overflow-hidden bg-[#eef1f4]">
        <RouteMap corridor={corridor} dir={dir} className="absolute inset-0 h-full w-full" padBottom={120} />
        <div className="pointer-events-none absolute inset-x-0 top-0 h-[46%] bg-gradient-to-b from-white via-white/85 to-transparent" />
        <div className="pointer-events-none absolute inset-x-0 bottom-0 h-[44%] bg-gradient-to-t from-white via-white/90 to-transparent" />

        <div className="relative mx-auto max-w-[1200px] px-4 pt-[15vh] text-center">
          <p className="eyebrow">{DIR_LABEL[dir]} · {hm(card?.departAt)} 출발 기준</p>
          <h1 className="mt-2 text-[40px] sm:text-[52px] font-medium tracking-tight text-ink">{title}</h1>
          <p className="mt-2 min-h-[24px] text-[15px] sm:text-[17px] text-ink2">
            {now.loading && !card ? "판단 중…" : d?.summary ?? (now.error ? "판단 카드를 불러오지 못했습니다" : "")}
            {d && <a href="#evidence" className="ml-2 underline underline-offset-4 text-ink">근거 보기</a>}
          </p>
          <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
            <Select label="코리도" value={cid} onChange={(v) => set({ c: v })}
                    options={(corridors.data ?? [{ id: cid, name: cid } as any]).map((c: any) => ({ value: c.id, label: c.name }))} />
            <Segmented label="방향" value={dir} onChange={(v) => set({ dir: v })}
                       options={[{ value: "DN" as Dir, label: corridor ? `${corridor.originCity}→${corridor.destCity}` : "하행" },
                                 { value: "UP" as Dir, label: corridor ? `${corridor.destCity}→${corridor.originCity}` : "상행" }]} />
            <Segmented label="출발 시점" value={departIn} onChange={(v) => set({ t: v })} options={DEPART} />
            <Select label="역 접근 시간" value={String(accessMin)} onChange={(v) => set({ a: v })} options={ACCESS} />
          </div>
        </div>

        <div className="absolute inset-x-0 bottom-0 pb-10 sm:pb-14">
          <SpecStrip>
            <Spec value={car.big} unit={car.unit} label="자동차" tone="road" />
            <Spec value={train.big} unit={train.unit} label="기차" tone="rail" />
            <Spec value={d?.diffMin == null ? DASH : String(Math.abs(d.diffMin))} unit={d?.diffMin == null ? "" : "분"}
                  label={d?.verdict === "TRAIN" ? "기차가 빠름" : d?.verdict === "CAR" ? "자동차가 빠름" : "차이"} />
          </SpecStrip>
          <div className="mt-8 flex flex-col items-center justify-center gap-3 px-4 sm:flex-row sm:gap-6">
            <Link href={`/road/${cid}?dir=${dir}`} className="btn-primary">도로 분석</Link>
            <Link href={`/rail/${cid}?dir=${dir}`} className="btn-secondary">철도 분석</Link>
          </div>
        </div>
      </section>

      {/* ---------- 비교 */}
      <Section eyebrow="자동차와 기차" title="같은 출발 시각, 두 가지 선택" gray
               desc="자동차는 영업소 구간 통행시간의 합으로 예측하고, 기차는 최근 30일 실제 운행의 도착 지연을 더합니다.">
        <ErrorBox error={now.error} />
        {!card && now.loading && <Loading />}
        {card && <div className="grid gap-6 lg:grid-cols-2"><RoadTile card={card} /><RailTile card={card} /></div>}
      </Section>

      <Section id="evidence" eyebrow="R-DEC-01" title="왜 이렇게 판단했나요">
        {card ? <Evidence card={card} /> : <Loading />}
      </Section>

      <Section eyebrow="날씨 · 대기" title="출발지와 도착지" gray>
        {card ? <EnvRow card={card} /> : <Loading />}
      </Section>

      <Section eyebrow="돌발 안내" title="코리도 관련 최근 6시간">
        {card && card.incidents.length === 0 && <Empty>매칭된 돌발 안내가 없습니다.</Empty>}
        {card && card.incidents.length > 0 && (
          <ul className="space-y-3">
            {card.incidents.map((i) => (
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

      <section className="relative h-[560px] bg-cloud">
        <RouteMap corridor={corridor} dir={dir} interactive className="absolute inset-0 h-full w-full" />
        <div className="pointer-events-none absolute left-4 top-4 sm:left-8 sm:top-8 rounded bg-white/95 px-4 py-3 shadow-tile">
          <p className="text-sm font-medium">{corridor?.name ?? DASH} 노선</p>
          <p className="mt-1 flex items-center gap-2 text-xs text-muted"><span className="inline-block h-[3px] w-5 bg-road" />고속도로 구간 {corridor?.road[dir]?.segments ?? DASH}개 · {num(corridor?.road[dir]?.distanceKm, 0)}km</p>
          <p className="mt-1 flex items-center gap-2 text-xs text-muted"><span className="inline-block h-0 w-5 border-t-[3px] border-dashed border-rail" />철도 {corridor?.rail[dir]?.dep.name}–{corridor?.rail[dir]?.arr.name}</p>
        </div>
      </section>

      <Section eyebrow="코리도" title="자주 오가는 도시 구간" wide>
        <ErrorBox error={corridors.error} />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {(corridors.data ?? []).map((c) => (
            <button key={c.id} onClick={() => { set({ c: c.id }); window.scrollTo({ top: 0, behavior: "smooth" }); }}
                    className={`tile p-6 text-left transition hover:shadow-md ${c.id === cid ? "ring-2 ring-ink" : ""}`}>
              <p className="eyebrow">{c.id}</p>
              <p className="mt-1 text-xl font-medium">{c.name}</p>
              <p className="mt-3 text-sm text-muted">고속도로 {num(c.road.DN?.distanceKm, 0)}km · 구간 {c.road.DN?.segments}개</p>
              <p className="text-sm text-muted">철도 {c.rail.DN?.dep.name}역 – {c.rail.DN?.arr.name}역</p>
            </button>
          ))}
        </div>
      </Section>
    </Layout>
  );
}
