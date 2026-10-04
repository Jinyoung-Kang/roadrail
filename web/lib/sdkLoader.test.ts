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

test("시간 초과 뒤 다시 불러도 받던 스크립트를 재사용하고, 늦게 와도 성공한다 (WEB-08)", { timeout: 2000 }, async () => {
  // 느린 망에서 8초가 지나면 실패로 끝나고, 다시 부르면 <script> 를 또 붙여 SDK 를 두 번 실행할 수 있었다
  const { env, scripts, timers, arrive } = fakeEnv();
  const load = sdkLoader(env, "x");
  const a = load();
  timers[0]();                                  // 8초 초과
  await assert.rejects(a, /시간 초과/);
  const b = load();
  assert.equal(scripts.length, 1);              // 새로 붙이지 않는다
  arrive(scripts[0]);                           // 첫 스크립트가 늦게 도착
  assert.ok((await b).maps.LatLng);
});

test("스크립트는 왔지만 maps.load 전이면 load 만 부른다", { timeout: 2000 }, async () => {
  const { env, scripts, timers, arrive } = fakeEnv();
  const load = sdkLoader(env, "x");
  const a = load();
  timers[0]();
  await assert.rejects(a);
  arrive({ onload: null, onerror: null });      // 전역 객체는 생겼지만 첫 약속은 이미 끝남
  const k = await load();
  assert.ok(k.maps.LatLng);
  assert.equal(scripts.length, 1);
});
