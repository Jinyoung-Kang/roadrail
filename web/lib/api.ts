import { useCallback, useEffect, useRef, useState } from "react";
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

/** 간단한 조회 훅 — url 이 null 이면 호출하지 않는다. refreshMs 가 있으면 주기적으로 다시 부른다. */
export function useApi<T>(url: string | null, refreshMs?: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<HttpError | Error | null>(null);
  const [loading, setLoading] = useState(false);
  const seq = useRef(0);

  const load = useCallback(async () => {
    if (!url) return;
    const my = ++seq.current;
    setLoading(true);
    try {
      const d = await getJson<T>(url);
      if (my === seq.current) { setData(d); setError(null); }
    } catch (e) {
      if (my === seq.current) setError(e as Error);
    } finally {
      if (my === seq.current) setLoading(false);
    }
  }, [url]);

  useEffect(() => {
    load();
    if (!refreshMs) return;
    const id = setInterval(load, refreshMs);
    return () => clearInterval(id);
  }, [load, refreshMs]);

  return { data, error, loading, reload: load };
}

export const qs = (p: Record<string, string | number | undefined | null>) =>
  Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");
