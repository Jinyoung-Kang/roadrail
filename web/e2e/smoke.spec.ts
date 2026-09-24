import { expect, test } from "@playwright/test";

// 스택이 떠 있는 상태(make up)에서 실행: make e2e
test("판단 화면: 어디서 → 어디로 · 판단 요약 · 스펙 · 근거", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("서울역");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("대전역");
  await expect(page.getByText(/빠를 것으로 보입니다|비슷합니다|비교할 수 없습니다|비교합니다/).first()).toBeVisible();
  await expect(page.getByText("자동차", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("판단 근거 · R-DEC-01")).toBeVisible();
  await expect(page.getByText("데이터 시각")).toBeVisible();
});

test("판단 화면: ⇄ 로 어디서·어디로를 바꾼다", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("서울역");
  await page.getByRole("button", { name: "어디서와 어디로 바꾸기" }).click();
  await expect(page).toHaveURL(/from=%EB%8C%80%EC%A0%84/);  // 대전
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/대전역\s*→\s*서울역/);
});

test("판단 화면: 전국 어디든 검색해서 고른다", async ({ page }) => {
  await page.goto("/");
  const to = page.getByRole("combobox", { name: "어디로" });
  await to.fill("전주");
  await expect(page.getByRole("option").first()).toBeVisible({ timeout: 10_000 });
  await page.getByRole("option", { name: /전주시/ }).first().click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("전주시");
  await expect(page.getByText(/빠를 것으로 보입니다|비슷합니다|비교할 수 없습니다|비교합니다/).first()).toBeVisible({ timeout: 15_000 });
});

test("도로 분석: 전국 어디든 — 출발 시각별 · 도로 구성", async ({ page }) => {
  await page.goto("/road?from=%EA%B0%95%EB%82%A8%EC%97%AD~37.49790~127.02760~&to=%EC%A0%84%EC%A3%BC%EC%8B%9C~35.82420~127.14800~");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/강남역\s*→\s*전주시/);
  await expect(page.getByRole("heading", { name: "언제 출발하면 빠를까" })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole("heading", { name: "어떤 도로로 가나요" })).toBeVisible();
  await expect(page.getByText("고속도로 비율 (거리)")).toBeVisible();
});

test("지도: 기본은 잠김 — 스크롤해도 배율이 바뀌지 않고 버튼으로 조작", async ({ page }) => {
  await page.goto("/");
  const btn = page.getByRole("button", { name: "지도 조작하기 (이동 · 확대)" });
  await expect(btn).toBeVisible({ timeout: 15_000 });
  await btn.click();
  await expect(page.getByRole("button", { name: "지도 조작 끄기" })).toBeVisible();
});

test("선택 목록: 맨 아래 항목까지 잘리지 않는다", async ({ page }) => {
  await page.goto("/rail?dep=3900023&arr=3900073");
  await page.getByRole("combobox", { name: "도착역" }).click();
  const list = page.getByRole("listbox");
  await expect(list.getByRole("option").first()).toBeVisible();
  await list.evaluate((el) => (el.scrollTop = el.scrollHeight));
  const last = list.getByRole("option").last();
  await expect(last).toBeInViewport();
  const box = await last.boundingBox();
  const vh = page.viewportSize()!.height;
  expect(box!.y + box!.height).toBeLessThanOrEqual(vh);
});

test("고속도로 실측 분석(길): 추이 차트와 히트맵", async ({ page }) => {
  await page.goto("/road/SEL-DJN?dir=DN");
  await expect(page.getByRole("heading", { name: "통행시간과 기준선" })).toBeVisible();
  await expect(page.locator(".recharts-line").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "요일 × 시간 히트맵" })).toBeVisible();
});

test("철도 분석: 임의 역 쌍 — 역 검색으로 바꾼다", async ({ page }) => {
  await page.goto("/rail?dep=3900023&arr=3900073");
  await expect(page.getByText(/정시율 \(도착 ≤5분\)/)).toBeVisible();
  await page.getByRole("combobox", { name: "도착역" }).fill("부산");
  await page.getByRole("option", { name: /^부산역/ }).first().click();
  await expect(page).toHaveURL(/arr=3900114/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(/서울역\s*→\s*부산역/);
  await expect(page.getByRole("heading", { name: "정시율 랭킹" })).toBeVisible();
});

test("수집 상태: 작업 표와 예산", async ({ page }) => {
  await page.goto("/ops");
  await expect(page.getByText("road_travel_time").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "공급자별 오늘 호출 예산" })).toBeVisible();
});

test("모바일: 메뉴 드로어", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "메뉴" }).click();
  await expect(page.getByRole("dialog").getByRole("link", { name: "철도 분석" })).toBeVisible();
});
