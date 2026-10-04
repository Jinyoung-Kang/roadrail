/**
 * 지도 SDK 스크립트 로더 — DOM · 전역 객체는 주입받는다(단위 테스트는 가짜로).
 * 한 번만 받고, 받는 중에 다시 부르면 같은 약속을 돌려준다.
 */
export interface ScriptLike {
  onload: ((ev: any) => void) | null;
  onerror: ((ev: any) => void) | null;
}

export interface LoaderEnv {
  /** 전역 SDK 객체 (window.kakao) */
  sdk(): any;
  /** <script src> 를 만들어 문서에 붙인다 */
  appendScript(src: string): ScriptLike;
  setTimeout(fn: () => void, ms: number): unknown;
}

export function sdkLoader(env: LoaderEnv, src: string, timeoutMs = 8000): () => Promise<any> {
  let loader: Promise<any> | null = null;
  return () => {
    const k = env.sdk();
    if (k?.maps?.LatLng) return Promise.resolve(k);
    if (!loader) {
      loader = new Promise((resolve, reject) => {
        const s = env.appendScript(src);
        s.onload = () => { const kk = env.sdk(); if (kk?.maps) kk.maps.load(() => resolve(kk)); else reject(new Error("kakao 로드 실패")); };
        s.onerror = () => reject(new Error("kakao 스크립트 로드 실패 (도메인 등록 확인)"));
        env.setTimeout(() => reject(new Error("kakao 로드 시간 초과")), timeoutMs);
      }).catch((e) => { loader = null; throw e; });
    }
    return loader;
  };
}
