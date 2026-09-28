import { expect, test, type Page } from "@playwright/test";

// README 스크린샷: CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture
// 실데이터라 찍는 시각마다 경로 · 환승역 · 소통 · 돌발 건수가 달라진다 — 다시 찍으면 README 설명이 화면과 맞는지 함께 확인.
// road.png(추석 귀성길 서울→대전 하행 6시간 24분)는 그날의 기록이라 여기서 다시 찍지 않는다.
test.skip(!process.env.CAPTURE, "CAPTURE=1 일 때만");

const place = (name: string, lat: number, lon: number, stn = "") => encodeURIComponent(`${name}~${lat}~${lon}~${stn}`);
const JEONJU_BUSAN = `from=${place("전주시", 35.82407, 127.14814)}&to=${place("부산역", 35.1152, 129.04155, "3900114")}`;
const SEOUL_UIJEONGBU = `from=${place("서울역", 37.5547, 126.9707)}&to=${place("의정부역", 37.7386, 127.0459)}`;

/** 판단 화면은 카카오 경로가 온 뒤에 찍는다 (예전 trip.png 는 자동차 카드가 '경로 조회 중'인 채로 찍혔다) */
async function tripLoaded(page: Page) {
  await expect(page.getByText("판단 근거 · R-DEC-01")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("카카오 경로 조회 중")).toHaveCount(0, { timeout: 30_000 });
}

const shots: { name: string; path: string; height?: number; shoot: (page: Page, file: string) => Promise<void> }[] = [
  { name: "home", path: "/", shoot: async (page, file) => {
    await tripLoaded(page);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: file });
  } },
  { name: "trip", path: `/?${JEONJU_BUSAN}`, shoot: async (page, file) => {   // 자동차 카드(경로 소통 띠) · 기차 여정(환승)
    await tripLoaded(page);
    await page.locator("#compare").evaluate((el) => el.scrollIntoView());
    await page.waitForTimeout(1500);
    await page.screenshot({ path: file });
  } },
  { name: "trip-map", path: "/", shoot: async (page, file) => {   // 자동차 경로의 정체 구간 색 · 경로 위 돌발(도로공사 · 경찰청 UTIC)
    await tripLoaded(page);
    const map = page.locator("section#map");
    await map.scrollIntoViewIfNeeded();
    await page.waitForTimeout(4000);   // 지도 타일
    await map.screenshot({ path: file });
  } },
  { name: "road-route", path: `/road?${SEOUL_UIJEONGBU}`, height: 1130, shoot: async (page, file) => {   // 제목부터 지도 끝까지
    await expect(page.getByRole("heading", { name: "어떤 도로로 가나요" })).toBeVisible({ timeout: 30_000 });
    await page.waitForTimeout(4000);
    await page.screenshot({ path: file });
  } },
  { name: "rail", path: "/rail?dep=3900023&arr=3900073", shoot: async (page, file) => {
    await page.waitForTimeout(3000);
    await page.screenshot({ path: file });
  } },
  { name: "ops", path: "/ops", shoot: async (page, file) => {
    await page.waitForTimeout(3000);
    await page.screenshot({ path: file });
  } },
];

for (const { name, path, height, shoot } of shots) {
  test(`capture ${name}`, async ({ page }) => {
    if (height) await page.setViewportSize({ width: 1440, height });
    await page.goto(path, { waitUntil: "networkidle" });
    await shoot(page, `../docs/images/${name}.png`);
  });
}
