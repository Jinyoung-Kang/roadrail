import assert from "node:assert/strict";
import { test } from "node:test";
import { ACCESS_OPTIONS, DEPART_OPTIONS, forPlaces } from "./trip.ts";

test("응답은 지금 고른 두 곳의 것일 때만", () => {
  const data = { from: { name: "서울역" }, to: { name: "대전역" }, v: 1 };
  assert.equal(forPlaces(data, { name: "서울역" }, { name: "대전역" }), data);
  assert.equal(forPlaces(data, { name: "서울역" }, { name: "부산역" }), null);   // 도착지를 바꾼 직후
  assert.equal(forPlaces(data, null, { name: "대전역" }), null);
  assert.equal(forPlaces(null, { name: "서울역" }, { name: "대전역" }), null);
});

test("출발 시점 · 역까지 시간 선택지", () => {
  assert.deepEqual(DEPART_OPTIONS.map((o) => o.label), ["지금", "+30분", "+1시간", "+2시간", "+3시간"]);
  assert.deepEqual(ACCESS_OPTIONS.map((o) => o.value), ["auto", "10", "20", "30", "45"]);
});
