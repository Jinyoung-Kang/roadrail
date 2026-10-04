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
}

export const browserEnv = (): QueryEnv => ({
  setTimeout: (fn, ms) => window.setTimeout(fn, ms),
  clearTimeout: (id) => window.clearTimeout(id as number),
});

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
                               env: QueryEnv): Query {
  let state: QueryState<T> = EMPTY;
  let seq = 0, timer: unknown = null, period = 0, disposed = false;
  const emit = (patch: Partial<QueryState<T>>) => { state = { ...state, ...patch }; onChange(state); };
  const stop = () => { if (timer !== null) env.clearTimeout(timer); timer = null; };
  const arm = () => {
    stop();
    if (period > 0 && state.url !== null) timer = env.setTimeout(() => { timer = null; arm(); void reload(); }, period);
  };

  async function reload() {
    const url = state.url;
    if (disposed || url === null) return;
    const my = ++seq;
    emit({ loading: true });
    try {
      const d = await fetcher(url);
      if (my === seq && !disposed) emit({ data: d, dataUrl: url, error: null, errorUrl: null, loading: false });
    } catch (e) {
      if (my === seq && !disposed) emit({ error: e as Error, errorUrl: url, loading: false });
    }
  }

  return {
    setUrl(url) {
      if (disposed || url === state.url) return;
      seq++;  // 이전 주소의 진행 중 응답은 버린다
      emit({ url, loading: false });
      arm();
      void reload();
    },
    reload,
    every(ms) { period = ms; arm(); },
    dispose() { disposed = true; seq++; stop(); },
  };
}
