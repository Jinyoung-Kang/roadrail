import { expect, test } from "@playwright/test";

// QA API 계약(웹 프록시 경유) — 개발 스택: E2E_CHANNEL=chrome npx playwright test qa-api

test("QA-06 아주 긴 주소도 오류 규약(JSON {code, message})으로 답한다 — HTML 오류 페이지를 넘기지 않음", async ({ request }) => {
  // 수정 전: 1만 자 검색어 → API 서버(Tomcat)가 HTML 400 을 주고 프록시가 그대로 넘김(text/html)
  const r = await request.get(`/api/v1/places/search?q=${"a".repeat(10_000)}`);
  expect(r.status()).toBeGreaterThanOrEqual(400);
  expect(r.status()).toBeLessThan(500);
  expect(r.headers()["content-type"]).toContain("application/json");
  const body = await r.json();
  expect(body.code).toBeTruthy();
  expect(body.message).toBeTruthy();
});
