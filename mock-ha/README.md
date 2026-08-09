# mock-ha — scriptable fake Home Assistant

A standalone Node server that speaks enough of the
[`home-assistant-js-websocket`](https://github.com/home-assistant/home-assistant-js-websocket)
protocol to drive Hawksnest's **real** `haSource` against scripted scenarios —
**without ever touching a real lock**. It backs the Playwright E2E suite, and the
same process + contract is meant to be reused by the Android instrumented tests.

```bash
npm run mock-ha                      # port 8765 (override with MOCK_HA_PORT= or PORT=)
curl localhost:8765/__scenario/health
```

The port is defined once in `mock-ha/port.ts` (default 8765) and read by the server, the
control client, the Playwright `webServer`, and the E2E fixtures — set `MOCK_HA_PORT` to move
them all together (needed on the Dragonfly host, where 8765 is taken by the kidbot container).

The app connects when its credentials point at the mock, e.g.
`localStorage["hawksnest.ha"] = {"url":"http://localhost:8765","token":"e2e-token"}`
(the lib connects to `ws://localhost:8765/api/websocket`).

## Layout

| File | Role |
|------|------|
| `wsProtocol.ts` | Transport-agnostic protocol state machine + `MockHub` (state + broadcast). Unit-tested in `__tests__/`. |
| `server.ts` | HTTP + `ws` server; control API + REST; entry for `npm run mock-ha`. |
| `controlClient.ts` | `MockControl` HTTP client used by the E2E specs. |
| `scenarios/` | Initial entity snapshot, registries, history, and named scenarios. |

## Scenarios (the cross-harness contract)

Named, requested via the control API. Each is a fresh, isolated copy on reset.

| Name | Behaviour |
|------|-----------|
| `default` | Everything healthy; locks confirm after `delayMs` (600 default). |
| `lock-jam` | `lock.lock` echoes `jammed` — never reaches `locked`. |
| `bad-token` | Auth always fails → app shows "Invalid access token." |
| `ring-camera` | Adds a ring-mqtt split camera (Front Gate: `_live`/`_snapshot`/`_event` + event selector + motion/ding) for recorded-playback specs. Pair with `/stream-outcome` to script failures. |
| `frigate-camera` | Adds a Frigate-recorded camera (Front Gate) carrying the `client_id` + `camera_name` attributes `isFrigateCamera` requires — the backend clip export needs. No event selector, so the backend resolves `frigate` rather than `ring`. |

## Control API

All under `/__scenario/`. JSON bodies. CORS is permissive so the browser app
origin can reach the REST endpoints.

| Method + path | Body | Effect |
|---|---|---|
| `GET /health` | — | `{ok:true}` — Playwright waits on this. |
| `POST /reset` | `{scenario}` | Load a scenario; push its full state to live clients. |
| `POST /state` | `{entity_id, state, attributes?}` | Push one state change over the live subscription (e.g. fire a doorbell `_ding`). |
| `POST /service-outcome` | `{domain, service, entity_id?, outcome, delayMs?, state?}` | Script how the next matching `call_service` resolves. |
| `POST /stream-outcome` | `{entity_id?, outcome, delayMs?}` | Script how `camera/stream` resolves for an entity (omit `entity_id` to apply to all). |
| `POST /clip-outcome` | `{outcome}` | `"ok"` serves an mp4; `"empty"` returns Frigate's "no recordings found" 400 for the clip-export route. |
| `POST /disconnect` | — | Drop all live sockets; the app auto-reconnects. |
| `GET /calls` | — | The recorded `call_service` log (round-trip assertions). |
| `GET /stats` | — | `{connections, sessions, streamRequests}` — reconnect + stream-retry assertions. |

`outcome` ∈ `confirm` (echo the resulting state) · `jammed` (echo `jammed`) ·
`reject` (fail the call → card error) · `silent` (ack, never echo → pending hangs).

Stream `outcome` ∈ `ok` (mock HLS URL, optionally after `delayMs`) · `error` (fail the
command) · `timeout` (never reply — the app's own 15 s bound steps down; prefer `error`
in specs for speed). Delayed service echoes are cancelled on `reset`, so one test's
in-flight echo can never land in the next test's scenario.

## Protocol notes

- Client sends `{type:"auth", access_token}` on open → `auth_ok` (with
  `ha_version`) or `auth_invalid` + close.
- `ha_version` default `2024.12.0` → modern path: client sends `supported_features`
  (id 1) and `subscribe_entities`. State is pushed in the `a` (full-set) form.
  Command ids are echoed, never assumed.
- `call_service` carries the entity in `target.entity_id`; the delayed state echo
  over the entity subscription is what drives the non-optimistic lock UI.
- REST: automation config (`GET`→404 / writes→200), `frigate/events`→`[]`, a stub
  HLS playlist. Live video is never exercised headless.
- `auth/sign_path` returns the path with `?authSig=mock-sig` appended. Its absence used to make
  every signing attempt burn the client's full 10 s timeout before falling back to unsigned.
- `frigate/events/get` and `frigate/recordings/get` are **websocket** commands (the REST
  `/api/frigate/events` route does not exist on a real HA) and answer with an **undecoded JSON
  string**, exactly as the integration does — replying with an array would exercise a branch
  production never takes. The recordings set contains a deliberate 10-minute gap two hours back,
  which is what makes the clip-export coverage check testable.
- `/api/frigate/recording/<cam>/start/<s>/end/<e>` is the clip-export route: 401 without
  `authSig`, Frigate's "no recordings" 400 under `/__scenario/clip-outcome`, otherwise chunked
  `video/mp4` with **no `Content-Length`** — the reason no client can show export progress.
