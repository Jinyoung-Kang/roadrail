/**
 * 조회 하나의 상태 기계 — React 에 의존하지 않는다(단위 테스트는 node:test). useApi 가 이 위에 얹힌다.
 * 시계(타이머)는 주입받아, 테스트는 가짜 시계로 시간을 돌린다.
 */
export interface QueryState<T> {
  /** 지금 부르는 주소 */
  url: string | null;
  data: T | null;
  /** data · error 가 어느 주소의 결과인지 — 주소가 바뀐 뒤 이전 결과를 새 주소의 것처럼 보이지 않게 */
  dataUrl: string | null;
  error: Error | null;
  errorUrl: string | null;
  loading: boolean;
}

export interface QueryEnv {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(id: unknown): void;
  /** 탭이 숨겨졌나 — 숨긴 동안은 주기 갱신을 멈춘다 */
  hidden(): boolean;
  /** 탭이 다시 보이면 fn — 해제 함수를 돌려준다 */
  onVisible(fn: () => void): () => void;
}

export const browserEnv = (): QueryEnv => ({
  setTimeout: (fn, ms) => window.setTimeout(fn, ms),
  clearTimeout: (id) => window.clearTimeout(id as number),
  hidden: () => document.visibilityState === "hidden",
  onVisible: (fn) => {
    const h = () => { if (document.visibilityState === "visible") fn(); };
    document.addEventListener("visibilitychange", h);
    return () => document.removeEventListener("visibilitychange", h);
  },
});

export interface QueryOptions<T> {
  /** 서버가 '아직 받는 중'(pending 등)이라고 하면 retryMs 뒤 다시 — 최대 maxRetries 번, 그 뒤는 평소 주기 */
  retryWhile?: (data: T) => boolean;
  retryMs?: number;
  maxRetries?: number;
}

export const EMPTY: QueryState<never> = { url: null, data: null, dataUrl: null, error: null, errorUrl: null, loading: false };

/** 화면에 보일 값 — 지금 주소의 결과만. 주소가 바뀌고 새 결과가 오기 전에는 비우고 '불러오는 중' */
export function current<T>(s: QueryState<T>, url: string | null) {
  return {
    data: url !== null && s.dataUrl === url ? s.data : null,
    error: url !== null && s.errorUrl === url ? s.error : null,
    loading: url !== null && (s.loading || (s.dataUrl !== url && s.errorUrl !== url)),
  };
}

export interface Query {
  /** 부를 주소를 바꾼다(같으면 그대로). 진행 중인 이전 주소의 응답은 버린다. null 이면 멈춘다 */
  setUrl(url: string | null): void;
  /** 지금 주소를 다시 부른다 — 예전에 잡아 둔 콜백이 불러도 늘 최신 주소 */
  reload(): Promise<void>;
  /** ms 마다 지금 주소를 다시 부른다(0 이면 멈춤) */
  every(ms: number): void;
  dispose(): void;
}

export function createQuery<T>(fetcher: (url: string) => Promise<T>, onChange: (s: QueryState<T>) => void,
                               env: QueryEnv, options: () => QueryOptions<T> = () => ({})): Query {
  let state: QueryState<T> = EMPTY;
  let seq = 0, timer: unknown = null, period = 0, retries = 0, due = false, disposed = false;
  const emit = (patch: Partial<QueryState<T>>) => { state = { ...state, ...patch }; onChange(state); };
  const stop = () => { if (timer !== null) env.clearTimeout(timer); timer = null; };
  // 숨긴 탭에서는 부르지 않고 '밀림'만 표시 → 다시 보이면 바로 부른다
  const tick = () => { timer = null; if (env.hidden()) due = true; else void reload(); };
  const schedule = (ms: number) => { stop(); if (ms > 0 && state.url !== null) timer = env.setTimeout(tick, ms); };
  const unsubscribe = env.onVisible(() => { if (due && !disposed) void reload(); });

  async function reload() {
    const url = state.url;
    if (disposed || url === null) return;
    const my = ++seq;
    due = false;
    stop();
    emit({ loading: true });
    let fresh: { d: T } | null = null;
    try {
      const d = await fetcher(url);
      fresh = { d };
      if (my === seq && !disposed) emit({ data: d, dataUrl: url, error: null, errorUrl: null, loading: false });
    } catch (e) {
      if (my === seq && !disposed) emit({ error: e as Error, errorUrl: url, loading: false });
    }
    if (my !== seq || disposed) return;
    // 다음 호출: 서버가 아직 받는 중이면 짧게(상한까지), 아니면 평소 주기 — 끝난 뒤부터 잰다
    const o = options();
    if (fresh && o.retryWhile?.(fresh.d)) {
      if (retries < (o.maxRetries ?? 20)) {
        retries++;
        schedule(o.retryMs ?? 1500);
        return;
      }
    } else {
      retries = 0;
    }
    schedule(period);
  }

  return {
    setUrl(url) {
      if (disposed || url === state.url) return;
      seq++;  // 이전 주소의 진행 중 응답은 버린다
      retries = 0;
      stop();
      emit({ url, loading: false });
      void reload();
    },
    reload,
    every(ms) {
      period = ms;
      if (timer === null || ms === 0) schedule(ms);
    },
    dispose() { disposed = true; seq++; stop(); unsubscribe(); },
  };
}
