import { useCallback, useEffect, useRef, useState } from "react";
import { browserEnv, createQuery, type Query, type QueryState } from "./query";
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

/** 간단한 조회 훅 — url 이 null 이면 호출하지 않는다. refreshMs 가 있으면 주기적으로 다시 부른다.
 *  상태 · 순서 처리는 React 밖의 lib/query.ts 가 맡는다. */
export function useApi<T>(url: string | null, refreshMs?: number) {
  const [state, setState] = useState<QueryState<T>>({ data: null, error: null, loading: false });
  const query = useRef<Query | null>(null);

  useEffect(() => {
    const q = createQuery<T>((u) => getJson<T>(u), setState, browserEnv());
    query.current = q;
    return () => { q.dispose(); query.current = null; };
  }, []);

  const load = useCallback(async () => {
    if (url) await query.current?.load(url);
  }, [url]);

  useEffect(() => {
    load();
    if (url && refreshMs) query.current?.every(url, refreshMs);
    return () => query.current?.stop();
  }, [load, url, refreshMs]);

  return { data: state.data, error: state.error, loading: state.loading, reload: load };
}

export const qs = (p: Record<string, string | number | undefined | null>) =>
  Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");
