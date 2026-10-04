import { api } from "../api/client";
import { forPlaces } from "../trip";
import type { Place, Trip } from "../types";
import { useApi } from "./useApi";

/**
 * 출발지 → 도착지 판단 카드. 카카오 경로 · 날씨가 아직 오는 중(pending)이면 1.5초 뒤 다시(최대 20번 — 그 뒤는 1분 주기).
 * 서버는 조회를 계속해 캐시를 채운다. t 는 지금 고른 두 곳의 응답일 때만.
 */
export function useTrip(ready: boolean, from: Place | null, to: Place | null, departIn: number, access: string) {
  const url = ready && from && to ? api.trip(from, to, departIn, access === "auto" ? undefined : access) : null;
  const trip = useApi<Trip>(url, { refreshMs: 60_000, retryWhile: (d) => d.pending, retryMs: 1500, maxRetries: 20 });
  return { trip, t: forPlaces(trip.data, from, to) };
}
