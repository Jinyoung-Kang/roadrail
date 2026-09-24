import { test } from "@playwright/test";

// README 스크린샷: CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture
test.skip(!process.env.CAPTURE, "CAPTURE=1 일 때만");

const shots: [string, string, boolean?][] = [
  ["/?c=SEL-DJN&dir=DN", "home"],
  ["/?c=SEL-DJN&dir=DN#evidence", "home-full", true],
  ["/road/SEL-DJN?dir=DN", "road"],
  ["/rail/SEL-DJN?dir=DN", "rail"],
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
