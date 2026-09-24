// 브라우저는 같은 출처(/api/v1/*)로 부르고 Next 서버가 Spring Boot(api:8300)로 넘긴다 — CORS 불필요, 포트 노출 최소화.
// /api/v1 은 pages/api/v1/[...path].ts 프록시(실제 접속 주소를 X-Forwarded-For 로 — API 의 IP 별 요청 한도용),
// API 문서(/docs 등)만 rewrites. rewrites 는 빌드 시점에 고정되므로 도커 빌드 인자 API_INTERNAL_URL 로 주소를 넣는다.
const API = process.env.API_INTERNAL_URL || "http://localhost:8300";

// 보안 헤더 — 화면에서 쓰는 외부 출처만 허용한다: 카카오 지도 SDK(dapi.kakao.com → 지도 스크립트·타일 *.daumcdn.net;
// SDK 는 페이지와 같은 스킴으로 받으므로 http 로 여는 로컬에서는 http://*.daumcdn.net 도 필요 — 실측 CSP 위반으로 확인),
// Pretendard 글꼴(cdn.jsdelivr.net, SRI 고정). API 는 같은 출처(/api/v1 프록시)라 connect-src 'self'.
// Swagger UI(/docs, /swagger-ui, /v3) 는 자체 인라인 스크립트가 있어 이 CSP 에서 뺀다.
const CSP = [
  "default-src 'self'",
  "script-src 'self' https://dapi.kakao.com https://t1.daumcdn.net http://t1.daumcdn.net",
  "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net",
  "font-src 'self' data: https://cdn.jsdelivr.net",
  "img-src 'self' data: blob: https://*.daumcdn.net http://*.daumcdn.net https://*.kakao.com https://*.kakaocdn.net",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");
const SECURITY_HEADERS = [
  { key: "Content-Security-Policy", value: CSP },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },  // 카카오 JS 키 도메인 확인에 출처(origin)는 보낸다
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
];

/** @type {import('next').NextConfig} */
export default {
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path((?!docs|swagger-ui|v3/).*)", headers: SECURITY_HEADERS }];
  },
  async rewrites() {
    return [
      { source: "/docs", destination: `${API}/docs` },
      { source: "/swagger-ui/:path*", destination: `${API}/swagger-ui/:path*` },
      { source: "/v3/api-docs/:path*", destination: `${API}/v3/api-docs/:path*` },
      { source: "/v3/api-docs", destination: `${API}/v3/api-docs` },
    ];
  },
};
