// 브라우저는 같은 출처(/api/v1/*)로 부르고 Next 서버가 Spring Boot(api:8300)로 넘긴다 — CORS 불필요, 포트 노출 최소화.
// rewrites 는 빌드 시점에 고정되므로 도커 빌드 인자 API_INTERNAL_URL 로 주소를 넣는다.
const API = process.env.API_INTERNAL_URL || "http://localhost:8300";

/** @type {import('next').NextConfig} */
export default {
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
  async rewrites() {
    return [
      { source: "/api/v1/:path*", destination: `${API}/api/v1/:path*` },
      { source: "/docs", destination: `${API}/docs` },
      { source: "/swagger-ui/:path*", destination: `${API}/swagger-ui/:path*` },
      { source: "/v3/api-docs/:path*", destination: `${API}/v3/api-docs/:path*` },
      { source: "/v3/api-docs", destination: `${API}/v3/api-docs` },
    ];
  },
};
