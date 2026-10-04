import assert from "node:assert/strict";
import { test } from "node:test";
import { createQuery, current, type QueryEnv, type QueryOptions, type QueryState } from "./query.ts";

/** 가짜 시계 — advance(ms) 로 시간을 돌리면 그 사이 타이머를 차례로 실행한다 */
function fakeEnv() {
  let now = 0, next = 1, hidden = false;
  const timers = new Map<number, { at: number; fn: () => void }>();
  const visible: (() => void)[] = [];
  const env: QueryEnv = {
    setTimeout: (fn, ms) => { const id = next++; timers.set(id, { at: now + ms, fn }); return id; },
    clearTimeout: (id) => { timers.delete(id as number); },
    hidden: () => hidden,
    onVisible: (fn) => { visible.push(fn); return () => visible.splice(visible.indexOf(fn), 1); },
  };
  const setHidden = (h: boolean) => { hidden = h; if (!h) visible.forEach((fn) => fn()); };
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
  return { env, advance, setHidden, pending: () => timers.size };
}

const flush = () => new Promise<void>((r) => setImmediate(r));

/** 응답을 손으로 풀어 주는 가짜 fetch — 호출한 url 을 기록한다 */
function fakeFetch() {
  const calls: { url: string; resolve: (v: unknown) => void; reject: (e: Error) => void }[] = [];
  const fetcher = (url: string) => new Promise<unknown>((resolve, reject) => calls.push({ url, resolve, reject }));
  return { fetcher, calls };
}

function setup(options: QueryOptions<any> = {}) {
  const { env, advance, setHidden, pending } = fakeEnv();
  const { fetcher, calls } = fakeFetch();
  const states: QueryState<unknown>[] = [];
  const q = createQuery(fetcher, (s) => states.push(s), env, () => options);
  return { q, calls, advance, setHidden, pending, last: () => states[states.length - 1] };
}

test("성공하면 데이터, 실패하면 오류 — 오류여도 이전 데이터는 남긴다", async () => {
  const { q, calls, last } = setup();
  q.setUrl("/a");
  assert.equal(last().loading, true);
  calls[0].resolve({ v: 1 });
  await flush();
  assert.deepEqual(current(last(), "/a"), { data: { v: 1 }, error: null, loading: false });
  void q.reload();
  calls[1].reject(new Error("HTTP 500"));
  await flush();
  assert.deepEqual(last().data, { v: 1 });
  assert.equal(current(last(), "/a").error?.message, "HTTP 500");
});

test("늦게 온 이전 응답은 버린다 — 마지막 호출만 반영", async () => {
  const { q, calls, last } = setup();
  q.setUrl("/a");
  void q.reload();
  calls[1].resolve("A2");
  await flush();
  calls[0].resolve("A1");
  await flush();
  assert.equal(last().data, "A2");
});

test("주기 갱신: 응답이 온 뒤부터 ms 마다 다시 부르고, 정리하면 멈춘다", async () => {
  const { q, calls, advance, pending } = setup();
  q.setUrl("/a");
  q.every(60_000);
  calls[0].resolve(0);
  await flush();
  await advance(59_999);
  assert.equal(calls.length, 1);
  await advance(1);
  assert.equal(calls.length, 2);
  calls[1].resolve(1);
  await flush();
  await advance(60_000);
  assert.equal(calls.length, 3);
  q.dispose();
  assert.equal(pending(), 0);
  calls[2].resolve(2);
  await flush();
});

test("주소가 바뀌면 이전 주소의 결과를 새 주소의 것처럼 보이지 않는다 (WEB-05)", async () => {
  const { q, calls, last } = setup();
  q.setUrl("/rail?dep=A");
  calls[0].resolve("A 결과");
  await flush();
  q.setUrl("/rail?dep=B");
  assert.deepEqual(current(last(), "/rail?dep=B"), { data: null, error: null, loading: true });
  calls[1].resolve("B 결과");
  await flush();
  assert.equal(current(last(), "/rail?dep=B").data, "B 결과");
});

test("예전에 잡아 둔 reload 도 최신 주소를 부르고, 이전 주소의 늦은 응답은 버린다 (WEB-02)", async () => {
  // /rail 의 '시간표 받는 중' 재시도 타이머가 이전 역 쌍의 reload 를 잡고 있다가 불러, 새 역 쌍 화면을 덮어썼다
  const { q, calls, last } = setup();
  q.setUrl("/rail?dep=A");
  const heldReload = q.reload;
  q.setUrl("/rail?dep=B");
  void heldReload();
  assert.deepEqual(calls.map((c) => c.url), ["/rail?dep=A", "/rail?dep=B", "/rail?dep=B"]);
  calls[2].resolve("B 결과");
  await flush();
  calls[0].resolve("A 결과");  // 늦게 온 A
  await flush();
  assert.equal(last().dataUrl, "/rail?dep=B");
  assert.equal(last().data, "B 결과");
});

test("서버가 '아직 받는 중'이면 짧게 다시 부르되 횟수 상한까지만, 그 뒤는 평소 주기 (WEB-06)", async () => {
  // 카카오 경로가 계속 늦으면 1.5초마다 끝없이 다시 불렀다
  const { q, calls, advance } = setup({ retryWhile: (d: { pending: boolean }) => d.pending, retryMs: 1500, maxRetries: 3 });
  q.setUrl("/trip");
  q.every(60_000);
  for (let i = 0; i < 4; i++) {
    calls[i].resolve({ pending: true });
    await flush();
    await advance(1500);
  }
  assert.equal(calls.length, 4);                  // 처음 1 + 재시도 3
  await advance(60_000 - 1500);
  assert.equal(calls.length, 5);                  // 상한 뒤에는 평소 주기
  calls[4].resolve({ pending: false });
  await flush();
  await advance(1500);
  assert.equal(calls.length, 5);
});

test("탭을 숨기면 주기 갱신을 멈추고, 다시 보이면 밀린 갱신을 바로 한다 (WEB-06)", async () => {
  // 숨긴 탭도 60초마다 판단 카드를 불러, 열어 둔 탭 하나가 카카오 호출을 하루 약 144건 썼다(서버 캐시 10분)
  const { q, calls, advance, setHidden } = setup();
  q.setUrl("/trip");
  q.every(60_000);
  calls[0].resolve(1);
  await flush();
  setHidden(true);
  await advance(10 * 60_000);
  assert.equal(calls.length, 1);
  setHidden(false);
  assert.equal(calls.length, 2);
  calls[1].resolve(2);
  await flush();
  await advance(60_000);
  assert.equal(calls.length, 3);
});

test("숨긴 동안 밀린 갱신이 없으면 다시 보여도 부르지 않는다", async () => {
  const { q, calls, setHidden } = setup();
  q.setUrl("/trip");
  q.every(60_000);
  calls[0].resolve(1);
  await flush();
  setHidden(true);
  setHidden(false);
  assert.equal(calls.length, 1);
});
