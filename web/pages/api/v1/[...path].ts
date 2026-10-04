import type { IncomingMessage } from "http";
import type { NextApiRequest, NextApiResponse } from "next";

/**
 * /api/v1/* → Spring Boot(api:8300) 프록시.
 *
 * next.config 의 rewrites 로 넘기면 Next.js 가 X-Forwarded-For 를 붙이지 않고 클라이언트가 보낸 헤더를 그대로 넘긴다
 * → API 의 IP 별 요청 한도가 '웹 컨테이너 한 주소'로 묶이고, 클라이언트가 X-Forwarded-For 를 위조하면 한도를 피할 수 있었다(실측).
 * 여기서는 클라이언트가 보낸 X-Forwarded-For 를 버리고 **실제 접속 주소**로 덮어쓴다. 넘기는 헤더도 필요한 것만.
 * 경로는 원본 URL 을 그대로 넘기지 않고, 세그먼트를 검증해 /api/v1 아래로 다시 조립한다(인코딩한 '../' 로 다른 경로를 노리는 요청 차단).
 */
export const config = { api: { bodyParser: false, responseLimit: false } };

const API = process.env.API_INTERNAL_URL || "http://localhost:8300";
/** API 경로 세그먼트 — 영문 · 숫자 · '-' · '_' (예: corridors/SEL-DJN/now, admin/jobs/road_travel_time/run) */
const SEGMENT = /^[A-Za-z0-9_-]{1,64}$/;

/** '/api/v1/…?query' → 검증한 upstream 경로 (쿼리는 인코딩된 그대로), 올바르지 않으면 null */
function upstreamPath(url: string | undefined): string | null {
  const u = new URL(url ?? "", "http://proxy.local");  // WHATWG 파서가 '%2e%2e' 같은 점 세그먼트를 먼저 정규화한다
  if (!u.pathname.startsWith("/api/v1/")) return null;
  let segs: string[];
  try {
    segs = u.pathname.slice("/api/v1/".length).split("/").map(decodeURIComponent);
  } catch {
    return null;  // 잘못된 퍼센트 인코딩
  }
  return segs.every((s) => SEGMENT.test(s)) ? `/api/v1/${segs.join("/")}${u.search}` : null;
}
const REQ_HEADERS = ["accept", "accept-language", "content-type", "x-admin-token"];
const RES_HEADERS = ["content-type", "cache-control", "etag", "x-cache", "x-trace-id", "x-ratelimit-limit",
  "x-ratelimit-remaining", "retry-after", "allow"];
/** API 가 쓰는 메서드만 넘긴다 — 그 밖(TRACE · PUT · DELETE …)은 프록시에서 405 */
const METHODS = ["GET", "HEAD", "POST"];
const MAX_BODY = 64 * 1024;  // 관리 API 의 작은 JSON 만 받는다

async function readBody(req: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const c of req) {
    size += (c as Buffer).length;
    if (size > MAX_BODY) throw Object.assign(new Error("본문이 너무 큽니다"), { status: 413 });
    chunks.push(c as Buffer);
  }
  return Buffer.concat(chunks);
}

export default async function proxy(req: NextApiRequest, res: NextApiResponse) {
  if (!METHODS.includes(req.method ?? "")) {
    res.setHeader("Allow", METHODS.join(", "));
    res.status(405).json({ code: "METHOD_NOT_ALLOWED", message: "허용하지 않는 메서드입니다.", traceId: null });
    return;
  }
  const path = upstreamPath(req.url);
  if (!path) {
    res.status(400).json({ code: "VALIDATION_ERROR", message: "요청 경로가 올바르지 않습니다.", traceId: null });
    return;
  }
  const headers: Record<string, string> = {};
  for (const h of REQ_HEADERS) {
    const v = req.headers[h];
    if (typeof v === "string") headers[h] = v;
  }
  headers["x-forwarded-for"] = req.socket.remoteAddress ?? "";
  try {
    const body = req.method === "GET" || req.method === "HEAD" ? undefined : await readBody(req);
    const r = await fetch(API + path, {
      method: req.method, headers, body: body ? new Uint8Array(body) : undefined, redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    res.status(r.status);
    for (const h of RES_HEADERS) {
      const v = r.headers.get(h);
      if (v) res.setHeader(h, v);
    }
    res.send(Buffer.from(await r.arrayBuffer()));
  } catch (e) {
    const timedOut = (e as { name?: string }).name === "TimeoutError";
    const status = (e as { status?: number }).status ?? (timedOut ? 504 : 502);
    const [code, message] = status === 413 ? ["VALIDATION_ERROR", "요청 본문이 너무 큽니다."]
      : timedOut ? ["UPSTREAM_TIMEOUT", "API 서버 응답이 늦습니다. 잠시 뒤 다시 시도하세요."]
      : ["UPSTREAM_ERROR", "API 서버에 연결할 수 없습니다."];
    res.status(status).json({ code, message, traceId: null });
  }
}
