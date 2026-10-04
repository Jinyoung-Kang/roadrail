import { useCallback, useEffect, useRef, useState } from "react";
import { getJson } from "../api/client";
import { browserEnv, createQuery, current, EMPTY, type Query, type QueryOptions, type QueryState } from "../query";

export interface ApiOptions<T> extends QueryOptions<T> {
  /** 주기 갱신 — 탭을 숨긴 동안은 멈추고 다시 보이면 바로 갱신 */
  refreshMs?: number;
  /** 요청 옵션(예: 관리 토큰 머리글) — 부를 때마다 지금 값을 쓴다. 토큰은 주소에 넣지 않는다 */
  init?: RequestInit;
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
    const q = createQuery<T>((u) => getJson<T>(u, options.current.init), setState, browserEnv(), () => options.current);
    query.current = q;
    return () => { q.dispose(); query.current = null; };
  }, []);
  useEffect(() => { query.current?.setUrl(url); }, [url]);
  useEffect(() => { query.current?.every(o.refreshMs ?? 0); }, [o.refreshMs]);
  // 늘 지금 url 을 부른다 — 예전 렌더에서 잡아 둔 콜백이 불러도 이전 주소를 다시 부르지 않는다(WEB-02)
  const reload = useCallback(() => query.current?.reload() ?? Promise.resolve(), []);

  return { ...current(state, url), reload };
}
