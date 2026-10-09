import { api } from "../api/client";
import { forPlaces } from "../trip";
import type { Arrival, Place } from "../types";
import { useApi } from "./useApi";

/**
 * 도착 시각 기준 판단. 카카오 예측 · 시간표가 아직 오는 중(pending)이면 1.5초 뒤 다시(최대 20번).
 * arriveBy 가 null 이면(출발 시각 기준 모드) 부르지 않는다. 지금 고른 두 곳의 응답일 때만 돌려준다.
 */
export function useArrival(from: Place | null, to: Place | null, arriveBy: string | null, confidence: number, access: string) {
  const url = from && to && arriveBy ? api.arrival(from, to, arriveBy, confidence / 100, access === "auto" ? undefined : access) : null;
  const arrival = useApi<Arrival>(url, { retryWhile: (d) => d.pending, retryMs: 1500, maxRetries: 20 });
  return { arrival, a: forPlaces(arrival.data, from, to) };
}
