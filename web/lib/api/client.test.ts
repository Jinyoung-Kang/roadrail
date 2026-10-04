import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { api, errorText, getJson, HttpError, qs, runJob } from "./client.ts";

const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

test("쿼리 문자열은 빈 값을 빼고 인코딩한다", () => {
  assert.equal(qs({ a: "서울 역", b: 0, c: undefined, d: null, e: "" }), "a=%EC%84%9C%EC%9A%B8%20%EC%97%AD&b=0");
});

test("엔드포인트 주소는 예전 화면이 만들던 주소와 같다 (공개 API 그대로)", () => {
  const seoul = { name: "서울역", lat: 37.55, lon: 126.97, stationCode: "3900023" };
  const daejeon = { name: "대전역", lat: 36.33, lon: 127.43, stationCode: null };
  assert.equal(api.trip(seoul, daejeon, 30, undefined),
    "/api/v1/trip?fromLat=37.55&fromLon=126.97&fromName=%EC%84%9C%EC%9A%B8%EC%97%AD&fromStation=3900023"
    + "&toLat=36.33&toLon=127.43&toName=%EB%8C%80%EC%A0%84%EC%97%AD&departIn=30");
  assert.equal(api.placesSearch("전주 시"), "/api/v1/places/search?q=%EC%A0%84%EC%A3%BC%20%EC%8B%9C");
  assert.equal(api.stations({ limit: 400, sort: "name" }), "/api/v1/stations?limit=400&sort=name");
  assert.equal(api.stations({ limit: 60, sort: "name", q: "부산" }), "/api/v1/stations?limit=60&sort=name&q=%EB%B6%80%EC%82%B0");
  assert.equal(api.corridorForecast("SEL-DJN", "DN", [60, 120, 180]), "/api/v1/corridors/SEL-DJN/road/forecast?dir=DN&horizons=60,120,180");
  assert.equal(api.corridorBaseline("../x", "UP"), "/api/v1/corridors/..%2Fx/road/baseline?dir=UP");  // URL 에서 온 값은 경로 조각으로
  assert.equal(api.runJob("road_travel_time"), "/api/v1/admin/jobs/road_travel_time/run");
});

test("오류 응답은 HttpError(상태 · 서버 오류 코드), JSON 이 아닌 성공 응답은 오류", async () => {
  globalThis.fetch = (async () => new Response(JSON.stringify({ code: "RATE_LIMITED", message: "요청이 너무 많습니다" }), { status: 429 })) as typeof fetch;
  await assert.rejects(getJson("/x"), (e: unknown) => e instanceof HttpError && e.status === 429 && e.body?.code === "RATE_LIMITED");
  globalThis.fetch = (async () => new Response("<html>", { status: 200 })) as typeof fetch;
  await assert.rejects(getJson("/x"), /JSON 아님/);
  globalThis.fetch = (async () => new Response("<html>", { status: 502 })) as typeof fetch;
  await assert.rejects(getJson("/x"), (e: unknown) => e instanceof HttpError && e.status === 502 && e.body === null);
});

test("관리 명령은 토큰을 머리글로만 보내고, 오류는 코드와 함께 읽힌다", async () => {
  let seen: { url: string; init?: RequestInit } | null = null;
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    seen = { url, init };
    return new Response(JSON.stringify({ requestId: "01J" }), { status: 202 });
  }) as typeof fetch;
  assert.deepEqual(await runJob("kakao_eta", "SECRET123"), { requestId: "01J" });
  assert.equal(seen!.url, "/api/v1/admin/jobs/kakao_eta/run");
  assert.equal(seen!.init?.method, "POST");
  assert.equal((seen!.init?.headers as Record<string, string>)["X-Admin-Token"], "SECRET123");
  assert.equal(errorText(new HttpError(401, { code: "UNAUTHORIZED", message: "토큰이 없습니다" } as never)), "UNAUTHORIZED: 토큰이 없습니다");
});
