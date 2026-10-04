import { useCallback, useEffect, useRef, useState } from "react";
import { browserEnv, createQuery, current, EMPTY, type Query, type QueryOptions, type QueryState } from "./query";
import type { ApiError } from "./types";

export class HttpError extends Error {
  constructor(public status: number, public body: ApiError | null) {
    super(body?.message ?? `HTTP ${status}`);
  }
}

export async function getJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, { ...init, headers: { Accept: "application/json", ...(init?.headers ?? {}) } });
  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // JSON 이 아닌 본문(프록시 · 서버의 HTML 오류 페이지 등) — 오류면 상태 코드로 알리고, 성공인데 JSON 이 아니면 그대로 알린다
    if (res.ok) throw new Error("응답을 해석할 수 없습니다 (JSON 아님)");
  }
  if (!res.ok) throw new HttpError(res.status, body as ApiError | null);
  return body as T;
}

export interface ApiOptions<T> extends QueryOptions<T> {
  /** 주기 갱신 — 탭을 숨긴 동안은 멈추고 다시 보이면 바로 갱신 */
  refreshMs?: number;
}

/** 간단한 조회 훅 — url 이 null 이면 호출하지 않는다. 숫자를 주면 그 주기로 다시 부른다(= { refreshMs }).
 *  상태 · 순서 처리는 React 밖의 lib/query.ts 가 맡는다. data · error 는 지금 url 의 결과만 돌려준다(WEB-05). */
export function useApi<T>(url: string | null, opts?: number | ApiOptions<T>) {
  const o: ApiOptions<T> = typeof opts === "number" ? { refreshMs: opts } : opts ?? {};
  const [state, setState] = useState<QueryState<T>>(EMPTY);
  const query = useRef<Query | null>(null);
  const options = useRef(o);
  options.current = o;

  useEffect(() => {
    const q = createQuery<T>((u) => getJson<T>(u), setState, browserEnv(), () => options.current);
    query.current = q;
    return () => { q.dispose(); query.current = null; };
  }, []);
  useEffect(() => { query.current?.setUrl(url); }, [url]);
  useEffect(() => { query.current?.every(o.refreshMs ?? 0); }, [o.refreshMs]);
  // 늘 지금 url 을 부른다 — 예전 렌더에서 잡아 둔 콜백이 불러도 이전 주소를 다시 부르지 않는다(WEB-02)
  const reload = useCallback(() => query.current?.reload() ?? Promise.resolve(), []);

  return { ...current(state, url), reload };
}

export const qs = (p: Record<string, string | number | undefined | null>) =>
  Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");
