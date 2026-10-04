import assert from "node:assert/strict";
import { test } from "node:test";
import { sdkLoader, type LoaderEnv, type ScriptLike } from "./sdkLoader.ts";

function fakeEnv() {
  const scripts: ScriptLike[] = [];
  const timers: (() => void)[] = [];
  let global: any = undefined;
  const env: LoaderEnv = {
    sdk: () => global,
    appendScript: () => { const s: ScriptLike = { onload: null, onerror: null }; scripts.push(s); return s; },
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
  };
  // 스크립트가 도착 → 전역 객체가 생기고 maps.load 콜백에서 LatLng 이 준비된다
  const arrive = (s: ScriptLike) => {
    global = { maps: { load: (cb: () => void) => { global.maps.LatLng = class {}; cb(); } } };
    s.onload?.({});
  };
  return { env, scripts, timers, arrive };
}

test("받는 중에 여러 번 불러도 스크립트는 하나, 같은 결과", async () => {
  const { env, scripts, arrive } = fakeEnv();
  const load = sdkLoader(env, "https://dapi.kakao.com/sdk.js");
  const a = load(), b = load();
  assert.equal(scripts.length, 1);
  arrive(scripts[0]);
  assert.equal(await a, await b);
  assert.ok((await load()).maps.LatLng);
  assert.equal(scripts.length, 1);
});

test("스크립트 오류면 실패를 알리고, 다시 부르면 새로 받는다", async () => {
  const { env, scripts } = fakeEnv();
  const load = sdkLoader(env, "x");
  const a = load();
  scripts[0].onerror?.({});
  await assert.rejects(a, /스크립트 로드 실패/);
  void load().catch(() => {});
  assert.equal(scripts.length, 2);
});
