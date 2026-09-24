import type { IncomingMessage } from "http";
import type { NextApiRequest, NextApiResponse } from "next";

/**
 * /api/v1/* → Spring Boot(api:8300) 프록시.
 *
 * next.config 의 rewrites 로 넘기면 Next.js 가 X-Forwarded-For 를 붙이지 않고 클라이언트가 보낸 헤더를 그대로 넘긴다
 * → API 의 IP 별 요청 한도가 '웹 컨테이너 한 주소'로 묶이고, 클라이언트가 X-Forwarded-For 를 위조하면 한도를 피할 수 있었다(실측).
 * 여기서는 클라이언트가 보낸 X-Forwarded-For 를 버리고 **실제 접속 주소**로 덮어쓴다. 넘기는 헤더도 필요한 것만.
 */
export const config = { api: { bodyParser: false, responseLimit: false } };

const API = process.env.API_INTERNAL_URL || "http://localhost:8300";
const REQ_HEADERS = ["accept", "accept-language", "content-type", "x-admin-token"];
const RES_HEADERS = ["content-type", "cache-control", "etag", "x-cache", "x-trace-id", "x-ratelimit-limit",
  "x-ratelimit-remaining", "retry-after"];
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
  const headers: Record<string, string> = {};
  for (const h of REQ_HEADERS) {
    const v = req.headers[h];
    if (typeof v === "string") headers[h] = v;
  }
  headers["x-forwarded-for"] = req.socket.remoteAddress ?? "";
  try {
    const body = req.method === "GET" || req.method === "HEAD" ? undefined : await readBody(req);
    const r = await fetch(API + req.url, {
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
    const status = (e as { status?: number }).status ?? 502;
    res.status(status).json({ code: status === 413 ? "VALIDATION_ERROR" : "UPSTREAM_ERROR",
      message: status === 413 ? "요청 본문이 너무 큽니다." : "API 서버에 연결할 수 없습니다.", traceId: null });
  }
}
