import assert from "node:assert/strict";
import { test } from "node:test";
import { pickerItems } from "./picker.ts";

test("비어 있으면 추천 목록", () => {
  assert.deepEqual(pickerItems("  ", null, ["서울역"]), { items: ["서울역"], status: "suggest" });
});

test("검색어를 바꾼 직후에는 이전 검색어의 결과를 보이지 않는다 (WEB-04)", () => {
  const prev = { term: "전주", items: ["전주시"], failed: false };
  assert.deepEqual(pickerItems("수원", prev, []), { items: [], status: "loading" });
  assert.deepEqual(pickerItems(" 전주 ", prev, []), { items: ["전주시"], status: "ok" });
});

test("검색 실패는 '결과 없음'과 구분한다 (WEB-04)", () => {
  assert.equal(pickerItems("전주", { term: "전주", items: [], failed: true }, []).status, "error");
  assert.equal(pickerItems("전주", { term: "전주", items: [], failed: false }, []).status, "empty");
});
