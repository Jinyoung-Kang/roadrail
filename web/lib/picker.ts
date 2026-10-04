/** 검색형 선택기의 표시 규칙 — React 에 의존하지 않는다 */
export interface SearchResult<T> { term: string; items: T[]; failed: boolean }
export type PickerStatus = "suggest" | "loading" | "ok" | "empty" | "error";

/**
 * 지금 입력에 맞는 목록만 보인다. 결과는 어느 검색어의 것인지와 함께 들고 있어,
 * 검색어를 바꾼 직후(새 결과가 오기 전)에는 이전 검색어의 결과를 보이지도 · 고르지도 않는다 (WEB-04).
 */
export function pickerItems<T>(q: string, result: SearchResult<T> | null, suggestions: T[]): { items: T[]; status: PickerStatus } {
  const term = q.trim();
  if (!term) return { items: suggestions, status: "suggest" };
  if (!result || result.term !== term) return { items: [], status: "loading" };
  if (result.failed) return { items: [], status: "error" };
  return { items: result.items, status: result.items.length ? "ok" : "empty" };
}
