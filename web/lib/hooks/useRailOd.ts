import { useRouter } from "next/router";
import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import { daysUntilYesterday } from "../format";
import { DEFAULT_PAIR, timetablePending } from "../rail";
import type { Punctuality, Station, Trains } from "../types";
import { useApi } from "./useApi";

/** 처음 보는 역 쌍은 TAGO 시간표를 받는 동안 일부를 확인 불가로 둔다 → 4초 뒤 다시(최대 30번 = 2분, 서버도 시간당 상한) */
const PENDING = { retryWhile: (d: Punctuality) => d.timetablePending, retryMs: 4000, maxRetries: 30 };

/** 임의 역 쌍 철도 분석의 상태 · 조회 — 역 쌍은 URL(dep · arr), 기간 · 정시 기준 · 운행일은 화면 상태 */
export function useRailOd() {
  const router = useRouter();
  const dep = (router.query.dep as string) || DEFAULT_PAIR.dep;
  const arr = (router.query.arr as string) || DEFAULT_PAIR.arr;
  const [days, setDays] = useState(30);
  const [thr, setThr] = useState(5);
  const [date, setDate] = useState<string | null>(null);
  const [all, setAll] = useState(false);
  const [bandDays, setBandDays] = useState(90);   // 배상 기준 통계는 기간을 따로 (드문 사건이라 기본 90일)
  useEffect(() => { setDate(null); setAll(false); }, [dep, arr]);
  const go = (p: { dep?: string; arr?: string }) =>
    router.push({ pathname: "/rail", query: { dep, arr, ...p } }, undefined, { scroll: false });

  // 비어 있을 때는 운행 중인 모든 역을 가나다순으로 (목록 안에서 스크롤)
  const allStations = useApi<Station[]>(api.stations({ limit: 400, sort: "name" }));
  // 기간은 여는 날 기준 — 정적으로 미리 그린 HTML(빌드한 날)과 달라 하이드레이션이 깨지지 않게 라우터 준비 뒤에만 (WEB-01)
  const period = router.isReady ? daysUntilYesterday(new Date(), days) : null;
  const base = period && dep !== arr ? { dep, arr, ...period, thresholdMin: thr } : null;
  const byTrain = useApi<Punctuality>(base ? api.railPunctuality({ ...base, groupBy: "train" }) : null, PENDING);
  const byDow = useApi<Punctuality>(base ? api.railPunctuality({ ...base, groupBy: "dow" }) : null, PENDING);
  const byHour = useApi<Punctuality>(base ? api.railPunctuality({ ...base, groupBy: "hour" }) : null, PENDING);
  const trains = useApi<Trains>(base ? api.railTrains({ dep, arr, date }) : null);
  const bandPeriod = router.isReady ? daysUntilYesterday(new Date(), bandDays) : null;
  // 기간이 랭킹 표와 같으면 같은 응답을 쓴다 (같은 주소를 두 번 부르지 않게)
  const ownBands = useApi<Punctuality>(bandPeriod && dep !== arr && bandDays !== days
    ? api.railPunctuality({ dep, arr, ...bandPeriod, thresholdMin: thr, groupBy: "train" }) : null, PENDING);
  const bands = bandDays === days ? byTrain : ownBands;
  const ttPending = timetablePending(byTrain.data, byDow.data, byHour.data);
  // 운행표도 같은 시간표를 쓴다 — 이 역 쌍의 시간표 받기가 끝나면 한 번 다시
  const pair = `${dep}-${arr}`;
  const pendingFor = useRef<string | null>(null);
  useEffect(() => {
    if (ttPending) pendingFor.current = pair;
    else if (pendingFor.current === pair && byTrain.data) { pendingFor.current = null; trains.reload(); }
  }, [ttPending, pair, byTrain.data, trains.reload]);

  return {
    dep, arr, go, days, setDays, thr, setThr, date, setDate, all, setAll, period,
    allStations, byTrain, byDow, byHour, trains, ttPending, bandDays, setBandDays, bands,
    depName: byTrain.data?.depStation ?? trains.data?.depStation,
    arrName: byTrain.data?.arrStation ?? trains.data?.arrStation,
  };
}
