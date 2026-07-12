# CLAUDE.md — Hawksnest

A custom, opinionated front-end for **Home Assistant** — HA stays the backend/brain; Hawksnest
is a presentation layer over HA's WebSocket + REST APIs, styled in the **PULSE** design language.
One repo, three deliverables:

1. **Web SPA/PWA** (`src/`) — React + TypeScript (Vite), Tailwind mapped onto PULSE CSS tokens
   (`src/theme/tokens.css`, ported from Spotter — **not** the `design.pulse:pulse-ui` Gradle
   library the sibling Android apps use; that's Compose-only). Deployed as an nginx pod.
2. **Native Android app** (`android/`) — Kotlin/Compose, talks to HA directly over Tailscale
   with a long-lived token. Full guide: [android/README.md](android/README.md).
3. **k3s deployment** (`deploy/`) — the pod runs in the same cluster/namespace as HA itself
   (see the sibling `hawksnest-automation` repo) and its nginx reverse-proxies `/api` to HA so
   the browser is same-origin. Full guide: [deploy/README.md](deploy/README.md).

The root [README.md](README.md) covers features/dev/testing and is kept current — start there.
This file adds the things that are easy to get wrong and the suite context.

## Architecture invariants (violating these has broken production before)

- **nginx must NOT send `X-Forwarded-For` to HA.** With `use_x_forwarded_for` on and an
  untrusted proxy IP, HA **400s the request** (it does not ignore the header) — this killed all
  camera frames once. Either keep XFF off (current), or send it AND add the flannel pod CIDR
  `10.42.0.0/16` to HA's `trusted_proxies`. Never half-do it. Details: deploy/README.md.
- **The service worker never caches `/api`** and never touches the HA token (localStorage).
  Offline = app shell + Offline/Demo state, never stale entity data. Keep it that way when
  editing `vite.config`/SW code.
- **Android HA token is encrypted at rest** (`util/TokenCipher`, AES-256-GCM key in the Android
  Keystore) and **excluded from cloud-backup + device-transfer** (`res/xml/*_rules.xml`). The
  stored value is ciphertext, undecryptable off-device. `CredentialStore.migrateLegacyToken()`
  upgrades pre-encryption installs on start. Consequence: a clean reinstall / new phone requires
  re-entering the token — deliberate for a door-unlocking credential. (Web still stores the token
  in localStorage — a PWA can't Keystore-wrap; TLS + OAuth is its path.)
- **Locks are non-optimistic UI** — they show pending until HA confirms. Security-critical;
  don't "fix" the lag. The mock-ha E2E suite covers pending/jam/rejected lock flows precisely so
  this stays testable without a real lock.
- **Android cleartext config is deliberate** (`network_security_config.xml`): the HA host can be
  a bare `100.x` Tailscale IP, which a scoped `<domain-config>` cannot match. Tighten only by
  fronting the proxy with TLS. See the "Networking note" in android/README.md.
- **Camera model:** the backend is **ring-mqtt** (+ embedded go2rtc). Its split entities
  (`_live`/`_snapshot`/`_event` + selectors/ding/motion) are collapsed into one logical camera in
  `src/lib/cameraModel.ts` (web) / the Android equivalent. Recorded playback = last ~5 Ring
  events via the event-selector entity, not continuous VOD (Frigate support exists for that,
  unused). WebRTC negotiates over the existing `/api/websocket`; media is UDP direct to go2rtc.

## Testing map (all real seams are covered without real hardware)

- `npm run test` — vitest: screens, stores, the mock-ha protocol, and `deploy.test.ts`, which
  asserts the deploy contract (nginx proxy config, Dockerfile, NodePort 30080) — if you change
  deploy files, this test is the spec.
- `npm run test:e2e` — Playwright against **`mock-ha/`**, a scriptable fake HA speaking the real
  websocket protocol (auth, reconnect, doorbell, lock flows). Same mock serves the Android
  instrumented tests (`scripts/android-emulator-test.sh`, needs KVM) via the `/__scenario` API.
- CI: `ci.yml` (web: typecheck/lint/test/build + kubeconform on the k8s manifests),
  `android-ci.yml` (unit tests + assembleDebug + the advisory **Sift** design audit — Sift is a
  sibling public repo checked out by CI; it is not required locally unless running the audit).

## Suite membership (Dragonfly)

Hawksnest's Android app is one of the five suite apps managed by the **Dragonfly hub** (repo
`CDRaab01/Dragonfly` — its CLAUDE.md/BROKER.md document the suite architecture). Hawksnest's
share of it:

- **Signing:** release APKs use the shared suite key (secrets here are **`HAWKSNEST_`-prefixed**:
  `HAWKSNEST_KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`, alias `suite`). The
  release workflow verifies the signer against the pinned suite SHA-256 (`5a596c9e…`) and fails
  on mismatch.
- **Releases:** `android-release.yml` **on main** publishes a signed GitHub Release +
  `version.json` (versionCode = epoch minutes) on any `android/**` push, tagged
  **`android-vX.Y.Z`** to stay clear of web `v*` releases. Feature branches may carry an older
  artifact-only version of this workflow — main's is authoritative.
- **Deploys (web):** `deploy.yml` on the self-hosted **Linux** runner (labels
  `self-hosted, linux, dragonfly`, lives in WSL on the host) builds the image, imports it into
  k3s, and applies `deploy/k8s`.
- **Deliberately excluded from the broker/SSO:** Dragonfly's config broker and
  "Sign in with Dragonfly" SSO cover apps with suite backends. Hawksnest authenticates directly
  to Home Assistant with an HA token — there is no Hawksnest server and nothing to broker. Don't
  add `SuiteConfigReader`/AppAuth here without a real reason.

## Conventions

- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, the haSource/data flow, or the token layer. Silently-drifting
  docs are how a sibling app's API docs said `/plans` for a round (ROADMAP2 T2 #5c).
- No raw hex in components — everything routes through the PULSE tokens
  (`src/theme/tokens.css` / `tailwind.config.ts`; Android `ui/theme/`).
- `src/lib/cards.ts` (domain → card mapping) must never throw — unknown domains render
  `GenericCard`.
- Label/icon resolution is centralized (`src/lib/resolve.ts` + `src/config/overrides.ts`);
  add per-entity overrides there, not in components.
- Phase 4 web shipped (entity detail/history, cover/climate/media_player/fan, drag-and-drop,
  PWA). Android Phase 4 (push notifications via FCM + HA automation) is **not built**. Next web
  ideas: OAuth to HA (replace the long-lived token), light theme.
