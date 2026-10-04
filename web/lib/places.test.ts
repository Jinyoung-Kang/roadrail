// 화면이 아닌 순수 함수의 단위 테스트 — node:test (새 의존성 없음). 실행: npm test
import assert from "node:assert/strict";
import { test } from "node:test";
import { decodePlace, encodePlace } from "./places.ts";
import type { Place } from "./types";

const seoul: Place = { name: "서울역", address: null, lat: 37.554648, lon: 126.970607, kind: "STATION", stationCode: "3900023" };

test("장소는 URL 에 이름~위도~경도~역코드로 담고 그대로 되읽는다", () => {
  assert.equal(encodePlace(seoul), "서울역~37.55465~126.97061~3900023");
  assert.deepEqual(decodePlace(encodePlace(seoul)), { ...seoul, lat: 37.55465, lon: 126.97061 });
});

test("역코드가 없으면 일반 장소, 좌표가 없거나 틀리면 null", () => {
  assert.equal(decodePlace("대전시청~36.35~127.38~")?.kind, "PLACE");
  assert.equal(decodePlace("대전시청~x~127.38~"), null);
  assert.equal(decodePlace(undefined), null);
  assert.equal(decodePlace(["a", "b"]), null);
});
