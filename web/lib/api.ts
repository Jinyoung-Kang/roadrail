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
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new HttpError(res.status, body);
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
