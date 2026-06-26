# Hawksnest push notifications (FCM)

Hawksnest delivers security pushes through its **own** Firebase Cloud Messaging (FCM) path — HA's
built-in `mobile_app` notify targets the official Companion app only. The flow:

```
[HA automation] --(rest_command POST)--> [FCM] --> [Hawksnest app] --> per-severity notification
        ^                                                  |
        |                                                  v
   reads input_text.hawksnest_push_token  <--(app writes the device token on connect)
```

The app side ships dormant: with no FCM config it builds and runs exactly as before. Push turns on
once **(1)** the build carries an FCM project config and **(2)** the HA-side helper + automation
exist. No `google-services.json` is needed — the app initializes Firebase manually from BuildConfig.

## 1. Firebase project (one-time, owner)

1. Create a Firebase project at <https://console.firebase.google.com>.
2. Add an **Android app** with package name `com.hawksnest`.
3. From the app's config (the `google-services.json` it offers — you don't add the file, you copy
   four values out of it), note:
   - `project_id`
   - `mobilesdk_app_id` (the **Application ID**, looks like `1:1234567890:android:abcdef`)
   - the Android **API key** (`current_key`)
   - `project_number` (the **Sender ID**)
4. In **Project settings → Cloud Messaging**, confirm the **FCM v1 API** is enabled and create a
   service account / use the existing one for the HTTP v1 send call (used by HA below).

Provide the four values to the build via `android/local.properties` (local) **or** CI secrets/env:

```properties
fcm.project_id=your-project-id
fcm.application_id=1:1234567890:android:abcdef123456
fcm.api_key=AIzaSy...
fcm.sender_id=1234567890
```

(Env var names for CI: `FCM_PROJECT_ID`, `FCM_APPLICATION_ID`, `FCM_API_KEY`, `FCM_SENDER_ID`.)

The app's **Settings → Notifications** shows "Security push enabled" once these are present.

## 2. Home Assistant side (owner)

**a. Token helper** — add an `input_text` the app writes its device token into:

```yaml
# configuration.yaml
input_text:
  hawksnest_push_token:
    name: Hawksnest push token
    max: 255
```

The app writes this automatically (`input_text/set_value`) on connect and on token rotation, using
your long-lived token — no manual step beyond creating the helper.

**b. `rest_command`** — sends to FCM HTTP v1 (swap in your project id + an OAuth access token from
the service account; many setups use a long-lived token via a helper or the `google_cloud` add-on):

```yaml
rest_command:
  hawksnest_push:
    url: "https://fcm.googleapis.com/v1/projects/YOUR_PROJECT_ID/messages:send"
    method: POST
    headers:
      Authorization: "Bearer {{ access_token }}"
      Content-Type: application/json
    payload: >
      {"message":{"token":"{{ states('input_text.hawksnest_push_token') }}",
      "data":{"severity":"{{ severity }}","title":"{{ title }}","body":"{{ body }}",
      "entity_id":"{{ entity_id }}"}}}
```

**c. Automation** — fire on the events you care about. `severity` drives the channel:
`smoke`/`co`/`gas`/`leak`/`water`/`life_safety` → **Life safety** (always alerts, bypasses DND);
`intrusion`/`alarm`/`triggered`/`motion`/`door`/`window` → **Security**; anything else → **Activity**.

```yaml
automation:
  - alias: Hawksnest — alarm triggered
    trigger:
      - platform: state
        entity_id: alarm_control_panel.home
        to: "triggered"
    action:
      - service: rest_command.hawksnest_push
        data:
          severity: alarm
          title: "Alarm triggered"
          body: "{{ trigger.to_state.attributes.friendly_name }} is going off"
          entity_id: "{{ trigger.entity_id }}"
  - alias: Hawksnest — smoke detected
    trigger:
      - platform: state
        entity_id: binary_sensor.kitchen_smoke
        to: "on"
    action:
      - service: rest_command.hawksnest_push
        data:
          severity: smoke
          title: "Smoke detected"
          body: "Kitchen smoke alarm"
          entity_id: "{{ trigger.entity_id }}"
```

## 3. Data payload contract (app side)

`HawksnestMessagingService` reads these `data` keys (all optional except as noted):

| key         | use                                                          |
|-------------|-------------------------------------------------------------|
| `severity`  | routes to a channel (see mapping above); default → Activity |
| `title`     | notification title                                          |
| `body`      | notification text                                           |
| `entity_id` | deep-link target (opens the app; per-entity nav is a TODO)  |

## Deferred (follow-ups)
- Biometric-gated notification **action buttons** ("Lock now" / "Disarm").
- **Quiet hours** (never suppressing life-safety).
- **Deep-link** straight to the entity/camera (currently opens Home).
- A **"send test push"** round-trip button in Settings.
