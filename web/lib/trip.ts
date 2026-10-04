/** 판단 · 경로 분석 화면의 규칙 — React 에 의존하지 않는다 */

type Named = { name: string };

/**
 * 응답이 지금 고른 출발지 · 도착지의 것일 때만 돌려준다 — 이름이 다르면(바꾼 직후 · 예전 캐시) null.
 * 서버는 받은 이름을 그대로 돌려준다.
 */
export function forPlaces<T extends { from: Named; to: Named }>(data: T | null, from: Named | null, to: Named | null): T | null {
  return data && from && to && data.from.name === from.name && data.to.name === to.name ? data : null;
}

export const DEPART_OPTIONS = [0, 30, 60, 120, 180].map((v) => ({ value: v, label: v === 0 ? "지금" : `+${v >= 60 ? `${v / 60}시간` : `${v}분`}` }));
export const ACCESS_OPTIONS = [{ value: "auto", label: "역까지 실제 경로" }, ...[10, 20, 30, 45].map((v) => ({ value: String(v), label: `역까지 ${v}분` }))];
