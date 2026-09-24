import { test } from "@playwright/test";

// README 스크린샷: CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture
test.skip(!process.env.CAPTURE, "CAPTURE=1 일 때만");

const shots: [string, string, boolean?][] = [
  ["/", "home"],
  ["/?from=%EC%A0%84%EC%A3%BC%EC%8B%9C~35.82407~127.14814~&to=%EB%B6%80%EC%82%B0%EC%97%AD~35.11520~129.04155~3900114&t=300#compare", "trip"],
  ["/?from=%EC%A0%84%EC%A3%BC%EC%8B%9C~35.82407~127.14814~&to=%EB%B6%80%EC%82%B0%EC%97%AD~35.11520~129.04155~3900114&t=300#map", "trip-map"],
  ["/", "home-full", true],
  ["/road/SEL-DJN?dir=DN", "road"],
  ["/road?from=%EA%B0%95%EB%82%A8%EC%97%AD~37.49790~127.02760~&to=%EC%A0%84%EC%A3%BC%EC%8B%9C~35.82420~127.14800~", "road-route"],
  ["/rail?dep=3900023&arr=3900073", "rail"],
  ["/forecast", "forecast"],
  ["/ops", "ops"],
];

for (const [path, name, full] of shots) {
  test(`capture ${name}`, async ({ page }) => {
    await page.goto(path, { waitUntil: "networkidle" });
    await page.waitForTimeout(3000);
    await page.screenshot({ path: `../docs/images/${name}.png`, fullPage: !!full });
  });
}
