import { expect, test } from "@playwright/test";

// 스택이 떠 있는 상태(make up)에서 실행: make e2e
test("판단 화면: 제목 · 판단 요약 · 스펙 · 근거", async ({ page }) => {
  await page.goto("/?c=SEL-DJN&dir=DN");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("서울 → 대전");
  await expect(page.getByText(/빠를 것으로 보입니다|비슷합니다|비교할 수 없습니다/).first()).toBeVisible();
  await expect(page.getByText("자동차", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("link", { name: "도로 분석" }).first()).toBeVisible();
  await expect(page.getByText("판단 근거 · R-DEC-01")).toBeVisible();
  await expect(page.getByText("데이터 시각")).toBeVisible();
});

test("판단 화면: 방향을 바꾸면 URL 과 제목이 바뀐다", async ({ page }) => {
  await page.goto("/?c=SEL-DJN&dir=DN");
  await page.getByRole("radio", { name: "대전→서울" }).click();
  await expect(page).toHaveURL(/dir=UP/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("대전 → 서울");
});

test("도로 분석: 추이 차트와 히트맵", async ({ page }) => {
  await page.goto("/road/SEL-DJN?dir=DN");
  await expect(page.getByRole("heading", { name: "통행시간과 기준선" })).toBeVisible();
  await expect(page.locator(".recharts-line").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "요일 × 시간 히트맵" })).toBeVisible();
});

test("철도 분석: 정시율 스펙과 랭킹 표", async ({ page }) => {
  await page.goto("/rail/SEL-DJN?dir=DN");
  await expect(page.getByText(/정시율 \(도착 ≤5분\)/)).toBeVisible();
  await expect(page.getByRole("heading", { name: "정시율 랭킹" })).toBeVisible();
  await expect(page.locator("table").first().locator("tbody tr").first()).toBeVisible();
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
