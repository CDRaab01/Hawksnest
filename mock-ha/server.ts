/**
 * Standalone mock Home Assistant server. Serves the WS API at `/api/websocket`
 * (driven by wsProtocol.ts), the REST surface the app touches under `/api/...`,
 * and a `/__scenario/*` control API the E2E specs use to script behaviour.
 *
 *   npm run mock-ha            # PORT=8765 by default
 *   curl localhost:8765/__scenario/health
 *
 * No imports from `src/` — this is a faithful generic HA stand-in that the web
 * E2E harness and (later) Android instrumented tests both point at. See README.md
 * for the scenario/control contract.
 */
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { WebSocketServer } from "ws";
import { MockHub, Session, type Transport } from "./wsProtocol";
import { getScenario } from "./scenarios";

const PORT = Number(process.env.PORT ?? 8765);
const hub = new MockHub(getScenario(process.env.MOCK_SCENARIO ?? "default"));

// --- helpers ---------------------------------------------------------------

function cors(res: ServerResponse): void {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
}

function json(res: ServerResponse, status: number, body: unknown): void {
  cors(res);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function readBody(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (chunk) => (data += chunk));
    req.on("end", () => {
      if (!data) return resolve({});
      try {
        resolve(JSON.parse(data));
      } catch {
        resolve({});
      }
    });
  });
}

const asRecord = (v: unknown): Record<string, unknown> =>
  v && typeof v === "object" ? (v as Record<string, unknown>) : {};
const str = (v: unknown): string => (typeof v === "string" ? v : "");

// --- HTTP: control API + REST ---------------------------------------------

async function handleHttp(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const method = req.method ?? "GET";
  const url = new URL(req.url ?? "/", `http://localhost:${PORT}`);
  const path = url.pathname;

  if (method === "OPTIONS") {
    cors(res);
    res.writeHead(204);
    res.end();
    return;
  }

  // ---- control API ----
  if (path === "/__scenario/health") return json(res, 200, { ok: true });

  if (path === "/__scenario/reset" && method === "POST") {
    const body = asRecord(await readBody(req));
    hub.reset(getScenario(str(body.scenario) || "default"));
    return json(res, 200, { ok: true });
  }
  if (path === "/__scenario/state" && method === "POST") {
    const b = asRecord(await readBody(req));
    hub.pushState({
      entity_id: str(b.entity_id),
      state: str(b.state),
      attributes: b.attributes ? asRecord(b.attributes) : undefined,
    });
    return json(res, 200, { ok: true });
  }
  if (path === "/__scenario/service-outcome" && method === "POST") {
    const b = asRecord(await readBody(req));
    hub.setServiceOutcome({
      domain: str(b.domain),
      service: str(b.service),
      entity_id: b.entity_id ? str(b.entity_id) : undefined,
      outcome: str(b.outcome) as "confirm" | "jammed" | "reject" | "silent",
      delayMs: typeof b.delayMs === "number" ? b.delayMs : undefined,
      state: b.state ? str(b.state) : undefined,
    });
    return json(res, 200, { ok: true });
  }
  if (path === "/__scenario/disconnect" && method === "POST") {
    hub.disconnectAll();
    return json(res, 200, { ok: true });
  }
  if (path === "/__scenario/calls" && method === "GET") {
    return json(res, 200, hub.calls);
  }
  if (path === "/__scenario/stats" && method === "GET") {
    return json(res, 200, { connections: hub.connections, sessions: hub.sessionCount });
  }

  // ---- REST the app touches ----
  // Automation config CRUD: GET → 404 (treated as "new"), writes → 200.
  if (path.startsWith("/api/config/automation/config/")) {
    if (!str(req.headers.authorization).startsWith("Bearer ")) return json(res, 401, { message: "auth" });
    if (method === "GET") return json(res, 404, { message: "Not found" });
    return json(res, 200, { result: "ok" });
  }
  if (path === "/api/frigate/events") return json(res, 200, []);
  if (path === "/api/hls/mock/master.m3u8") {
    cors(res);
    res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl" });
    res.end("#EXTM3U\n#EXT-X-ENDLIST\n");
    return;
  }

  json(res, 404, { message: `No route for ${method} ${path}` });
}

// --- wire it up ------------------------------------------------------------

const server = createServer((req, res) => {
  void handleHttp(req, res);
});

const wss = new WebSocketServer({ server, path: "/api/websocket" });
wss.on("connection", (ws) => {
  const transport: Transport = {
    send: (m) => {
      if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(m));
    },
    close: () => ws.close(),
  };
  const session = new Session(hub, transport);
  ws.on("message", (data) => session.handleMessage(data.toString()));
  ws.on("close", () => hub.unregisterSession(session));
  ws.on("error", () => hub.unregisterSession(session));
});

server.listen(PORT, () => {
  console.log(`mock-ha listening on http://localhost:${PORT} (ws at /api/websocket)`);
});
