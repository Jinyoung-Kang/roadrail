/**
 * 조회 하나의 상태 기계 — React 에 의존하지 않는다(단위 테스트는 node:test). useApi 가 이 위에 얹힌다.
 * 시계(타이머)는 주입받아, 테스트는 가짜 시계로 시간을 돌린다.
 */
export interface QueryState<T> { data: T | null; error: Error | null; loading: boolean }

export interface QueryEnv {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(id: unknown): void;
}

export const browserEnv = (): QueryEnv => ({
  setTimeout: (fn, ms) => window.setTimeout(fn, ms),
  clearTimeout: (id) => window.clearTimeout(id as number),
});

export interface Query {
  /** url 을 부른다. 늦게 온 이전 응답은 버린다(가장 마지막 호출만 반영) */
  load(url: string): Promise<void>;
  /** ms 마다 url 을 다시 부른다(이전 주기는 멈춤) */
  every(url: string, ms: number): void;
  /** 주기 갱신을 멈춘다 */
  stop(): void;
  dispose(): void;
}

export function createQuery<T>(fetcher: (url: string) => Promise<T>, onChange: (s: QueryState<T>) => void,
                               env: QueryEnv): Query {
  let state: QueryState<T> = { data: null, error: null, loading: false };
  let seq = 0, timer: unknown = null, disposed = false;
  const emit = (patch: Partial<QueryState<T>>) => { state = { ...state, ...patch }; onChange(state); };
  const stop = () => { if (timer !== null) env.clearTimeout(timer); timer = null; };

  async function load(url: string) {
    if (disposed) return;
    const my = ++seq;
    emit({ loading: true });
    try {
      const d = await fetcher(url);
      if (my === seq && !disposed) emit({ data: d, error: null });
    } catch (e) {
      if (my === seq && !disposed) emit({ error: e as Error });
    } finally {
      if (my === seq && !disposed) emit({ loading: false });
    }
  }

  function every(url: string, ms: number) {
    stop();
    const tick = () => { timer = env.setTimeout(tick, ms); void load(url); };
    timer = env.setTimeout(tick, ms);
  }

  return { load, every, stop, dispose() { disposed = true; stop(); } };
}
