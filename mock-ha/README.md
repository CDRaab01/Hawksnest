# mock-ha — scriptable fake Home Assistant

A standalone Node server that speaks enough of the
[`home-assistant-js-websocket`](https://github.com/home-assistant/home-assistant-js-websocket)
protocol to drive Hawksnest's **real** `haSource` against scripted scenarios —
**without ever touching a real lock**. It backs the Playwright E2E suite, and the
same process + contract is meant to be reused by the Android instrumented tests.

```bash
npm run mock-ha                      # PORT=8765 (override with PORT=…)
curl localhost:8765/__scenario/health
```

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

## Control API

All under `/__scenario/`. JSON bodies. CORS is permissive so the browser app
origin can reach the REST endpoints.

| Method + path | Body | Effect |
|---|---|---|
| `GET /health` | — | `{ok:true}` — Playwright waits on this. |
| `POST /reset` | `{scenario}` | Load a scenario; push its full state to live clients. |
| `POST /state` | `{entity_id, state, attributes?}` | Push one state change over the live subscription (e.g. fire a doorbell `_ding`). |
| `POST /service-outcome` | `{domain, service, entity_id?, outcome, delayMs?, state?}` | Script how the next matching `call_service` resolves. |
| `POST /disconnect` | — | Drop all live sockets; the app auto-reconnects. |
| `GET /calls` | — | The recorded `call_service` log (round-trip assertions). |

`outcome` ∈ `confirm` (echo the resulting state) · `jammed` (echo `jammed`) ·
`reject` (fail the call → card error) · `silent` (ack, never echo → pending hangs).

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
