import { Head, Html, Main, NextScript } from "next/document";

export default function Document() {
  return (
    <Html lang="ko">
      <Head>
        <meta name="description" content="고속도로·열차 이동 판단 & 정시성 분석 — 지금 차로 갈까, 기차로 갈까" />
        <link rel="icon" href="/favicon.svg" type="image/svg+xml" />
        {/* 외부 CSS 는 무결성(SRI) 고정 — jsDelivr 의 .min 은 동적으로 만들어져 SRI 가 깨질 수 있어 정적 원본을 쓴다 */}
        <link rel="stylesheet" crossOrigin="anonymous"
              integrity="sha384-2nNKoOPayicGa+aRguOQuiZP+RqQ4G3jalfDeOgftkKD7zBM2gJXTwcFqCZltdv0"
              href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.css" />
      </Head>
      <body>
        <Main />
        <NextScript />
      </body>
    </Html>
  );
}
