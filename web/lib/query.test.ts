import assert from "node:assert/strict";
import { test } from "node:test";
import { createQuery, type QueryEnv, type QueryState } from "./query.ts";

/** 가짜 시계 — advance(ms) 로 시간을 돌리면 그 사이 타이머를 차례로 실행한다 */
function fakeEnv() {
  let now = 0, next = 1;
  const timers = new Map<number, { at: number; fn: () => void }>();
  const env: QueryEnv = {
    setTimeout: (fn, ms) => { const id = next++; timers.set(id, { at: now + ms, fn }); return id; },
    clearTimeout: (id) => { timers.delete(id as number); },
  };
  async function advance(ms: number) {
    const end = now + ms;
    for (;;) {
      const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      timers.delete(due[0]);
      now = due[1].at;
      due[1].fn();
      await flush();
    }
    now = end;
  }
  return { env, advance, pending: () => timers.size };
}

const flush = () => new Promise<void>((r) => setImmediate(r));

/** 응답을 손으로 풀어 주는 가짜 fetch — 호출한 url 을 기록한다 */
function fakeFetch() {
  const calls: { url: string; resolve: (v: unknown) => void; reject: (e: Error) => void }[] = [];
  const fetcher = (url: string) => new Promise<unknown>((resolve, reject) => calls.push({ url, resolve, reject }));
  return { fetcher, calls };
}

function setup() {
  const { env, advance, pending } = fakeEnv();
  const { fetcher, calls } = fakeFetch();
  const states: QueryState<unknown>[] = [];
  const q = createQuery(fetcher, (s) => states.push(s), env);
  return { q, calls, advance, pending, last: () => states[states.length - 1] };
}

test("성공하면 데이터, 실패하면 오류 — 오류여도 이전 데이터는 남긴다", async () => {
  const { q, calls, last } = setup();
  void q.load("/a");
  assert.equal(last().loading, true);
  calls[0].resolve({ v: 1 });
  await flush();
  assert.deepEqual(last(), { data: { v: 1 }, error: null, loading: false });
  void q.load("/a");
  calls[1].reject(new Error("HTTP 500"));
  await flush();
  assert.deepEqual(last().data, { v: 1 });
  assert.equal(last().error?.message, "HTTP 500");
});

test("늦게 온 이전 응답은 버린다 — 마지막 호출만 반영", async () => {
  const { q, calls, last } = setup();
  void q.load("/a");
  void q.load("/b");
  calls[1].resolve("B");
  await flush();
  calls[0].resolve("A");
  await flush();
  assert.equal(last().data, "B");
});

test("주기 갱신: ms 마다 다시 부르고, 정리하면 멈춘다", async () => {
  const { q, calls, advance, pending } = setup();
  q.every("/a", 60_000);
  await advance(59_999);
  assert.equal(calls.length, 0);
  await advance(1);
  assert.equal(calls.length, 1);
  calls[0].resolve(1);
  await advance(60_000);
  assert.equal(calls.length, 2);
  q.dispose();
  assert.equal(pending(), 0);
  calls[1].resolve(2);
  await flush();
});
