// 부하 측정 (QA 스택 — 외부 API 키 없음, 요청 한도는 QA_RATE_LIMIT_* 로 올려 둔다)
//   k6 run -e BASE=http://127.0.0.1:8301 -e VUS=10 -e DUR=30s qa/load.k6.js
// 엔드포인트마다 태그를 달아 p50 · p95 · p99 · 처리량을 따로 본다. 캐시 적중(같은 요청)과 캐시 미스(날짜를 바꾼 요청)를 나눈다.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://127.0.0.1:8301';

const STATIONS = ['서울', '대전', '부산', '광주', '수원', '천안', '오송', '동대구', '익산', '강릉'];
const TRIP = '/api/v1/trip?fromLat=37.5547&fromLon=126.9706&fromName=%EC%84%9C%EC%9A%B8%EC%97%AD'
  + '&toLat=36.3324&toLon=127.4342&toName=%EB%8C%80%EC%A0%84%EC%97%AD&departIn=0';
const pad = (n) => String(n).padStart(2, '0');

const REQUESTS = [
  ['corridors', () => '/api/v1/corridors'],
  ['now(캐시)', () => '/api/v1/corridors/SEL-DJN/now?dir=DN'],
  ['trip(캐시)', () => TRIP],
  ['punct30(캐시)', () => '/api/v1/rail/od/punctuality?dep=3900023&arr=3900073&from=2026-09-04&to=2026-10-03&groupBy=train'],
  ['punct(미스)', () => {   // 날짜 범위를 바꿔 결과 캐시를 피한다
    const d = 1 + Math.floor(Math.random() * 28);
    return `/api/v1/rail/od/punctuality?dep=3900023&arr=3900114&from=2026-08-${pad(d)}&to=2026-09-${pad(d)}&groupBy=dow`;
  }],
  ['trains', () => '/api/v1/rail/od/trains?dep=3900023&arr=3900073'],
  ['stations', () => `/api/v1/stations?q=${encodeURIComponent(STATIONS[Math.floor(Math.random() * STATIONS.length)])}&limit=20`],
  ['series48h', () => '/api/v1/corridors/SEL-DJN/road/series?dir=DN&agg=5m'],
  ['collect-status', () => '/api/v1/ops/collect-status'],
];

export const options = {
  scenarios: { mixed: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: __ENV.DUR || '30s' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // 이름별 요약을 내려면 임계값에 이름을 걸어야 한다(k6)
  thresholds: Object.fromEntries([['http_req_failed', ['rate<0.01']], ...REQUESTS.map(([n]) => [`http_req_duration{name:${n}}`, ['p(95)<5000']])]),
};

export default function () {
  const [name, path] = REQUESTS[Math.floor(Math.random() * REQUESTS.length)];
  const r = http.get(BASE + path(), { tags: { name } });
  check(r, { '2xx': (x) => x.status >= 200 && x.status < 300 });
}

export function handleSummary(data) {
  const rows = [];
  for (const [key, m] of Object.entries(data.metrics)) {
    const k = key.match(/^http_req_duration\{name:(.+)\}$/);
    if (k) rows.push(`${k[1].padEnd(16)} p50 ${m.values['p(50)'].toFixed(1)}ms  p95 ${m.values['p(95)'].toFixed(1)}ms  p99 ${m.values['p(99)'].toFixed(1)}ms  max ${m.values.max.toFixed(0)}ms`);
  }
  const d = data.metrics.http_req_duration.values, n = data.metrics.http_reqs.values;
  const f = data.metrics.http_req_failed.values;
  return {
    stdout: `VUS=${__ENV.VUS || 10} 전체: ${n.count}건 · ${n.rate.toFixed(1)} req/s · 실패율 ${(f.rate * 100).toFixed(2)}% · `
      + `p50 ${d['p(50)'].toFixed(1)}ms · p95 ${d['p(95)'].toFixed(1)}ms · p99 ${d['p(99)'].toFixed(1)}ms\n` + rows.sort().join('\n') + '\n',
  };
}
