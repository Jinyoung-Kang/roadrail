/** 테슬라 홈페이지 톤의 라이트 모드 — 흰 바탕, 짙은 회색 글자, 파란 주요 버튼, 4px 모서리. */
module.exports = {
  content: ["./pages/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#171a20",        // 본문 (Tesla #171A20)
        ink2: "#393c41",       // 보조
        muted: "#5c5e62",      // 설명
        faint: "#8e8e8e",
        line: "#e2e3e3",
        cloud: "#f4f4f4",      // 보조 버튼 · 회색 면
        mist: "#f9f9f9",
        accent: "#3e6ae1",     // 주요 버튼 (Tesla blue)
        accentHover: "#3457b1",
        road: "#2a78d6",       // 차트 series-1 (자동차)
        rail: "#eb6834",       // 차트 series-2 (기차)
        aqua: "#1baf7a",       // 차트 series-3
        good: "#0ca30c",
        warn: "#fab219",
        serious: "#ec835a",
        crit: "#d03b3b",
      },
      fontFamily: {
        sans: ["Pretendard Variable", "Pretendard", "system-ui", "-apple-system", "Apple SD Gothic Neo", "Segoe UI", "sans-serif"],
      },
      letterSpacing: { brand: "0.32em" },
      boxShadow: { tile: "0 0 0 1px rgb(0 0 0 / 0.06)" },
    },
  },
  plugins: [],
};
