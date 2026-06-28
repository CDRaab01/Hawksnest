# Publishing Hawksnest to Google Play (internal testing)

Hawksnest ships to a **private internal testing track** — visible only to testers you invite, with
instant updates and none of the production-review or closed-test gates. CI builds the release AAB
and publishes it automatically via [Gradle Play Publisher][gpp] (`:app:publishReleaseBundle`,
wired in `app/build.gradle.kts` and `.github/workflows/android-playstore.yml`).

## Signing model (important)

New Play apps use **Play App Signing**: Google holds the real *app signing key* and re-signs every
install. You sign the **upload** with an *upload key*. Hawksnest reuses the committed key
(`app/hawksnest-debug.keystore`) as the upload key — zero secrets, CI publishes unattended.

- **Keep this key.** Once Play records it as your upload certificate, every future upload must use
  it. Losing it means asking Google to register a replacement upload key.
- The Play-installed app is signed by **Google's** key, not this one. A sideloaded *debug* build
  uses applicationId `com.hawksnest.debug` (a `.debug` suffix) so it coexists on the same device
  without a package conflict. A sideloaded *release* APK (`com.hawksnest`, this upload key) will
  conflict with the Play install — pick one channel per device, or uninstall to switch.

## One-time setup

Do these once, by hand, in order. Steps 1–3 are in the Play Console; step 4 is in Google Cloud.

### 1. Create the app
Play Console → **Create app**. Name "Hawksnest", app (not game), free. Accept the developer
declarations. Leave Play App Signing at its default (enrolled).

### 2. Bootstrap the first release manually
GPP can publish updates but **cannot create the app or its first release**. Build a signed AAB
locally and upload it once to establish the app entry + upload certificate:

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:bundleRelease           # → app/build/outputs/bundle/release/app-release.aab
```

Play Console → **Testing → Internal testing → Create new release** → upload that `.aab`. (No
`VERSION_CODE` env ⇒ versionCode `1`; CI later uses the git commit count, which is always higher.)
Save/review — you don't have to roll it out yet.

### 3. Add testers
Internal testing → **Testers** → add an email list or a Google Group, then copy the **opt-in URL**
and share it with your testers (each must accept once). Up to 100 testers.

### 4. Service account for automated publishing
Play Console → **Setup → API access** → link or create a Google Cloud project → **Create service
account** (this opens Google Cloud IAM). Create it, then back in Play Console **grant access** with
permission to *release to testing tracks* (Admin works too for a personal app). In Google Cloud,
open the service account → **Keys → Add key → JSON** → download it.

Add the JSON as a repository secret:

- GitHub → repo **Settings → Secrets and variables → Actions → New repository secret**
- Name: `PLAY_SERVICE_ACCOUNT_JSON`
- Value: the entire contents of the downloaded JSON file

## Cutting an internal release (every time after setup)

Bump the trigger and push to `main`:

```bash
# edit android/.release-trigger (e.g. "release 5" → "release 6")
git commit -am "android: cut Play internal release" && git push
```

The **Android — Play Internal** workflow builds the release AAB (signed with the committed upload
key) and publishes it to the internal track. You can also run it manually from the Actions tab
(**Run workflow**). Testers get the update through the Play Store.

`versionCode` is the git commit count (`git rev-list --count HEAD`) — monotonic and always above the
bootstrap upload's `1`, which is what Play requires.

### Publish from a workstation instead

Drop the same service-account JSON at `android/play-service-account.json` (git-ignored) and run:

```bash
cd android
VERSION_CODE=$(git rev-list --count HEAD) VERSION_NAME="0.1.$(git rev-list --count HEAD)" \
  ./gradlew :app:publishReleaseBundle
```

## Notes & gotchas

- **App content declarations.** Play requires a privacy policy URL, the Data safety form, content
  rating, and target-audience answers before a release can be fully rolled out. Internal testing is
  lenient about ordering, but fill these in (Play Console → *Policy → App content*) to avoid a
  blocked rollout. Be honest in Data safety: the app stores your HA URL + long-lived token
  on-device and talks only to your own Home Assistant.
- **Target API level.** `targetSdk = 35` meets the current new-app requirement; bump it when Google
  raises the bar.
- The sideload paths (`android-release.yml` APK, `android-ci.yml`) still work and are unaffected —
  they're just a different distribution channel from Play.

[gpp]: https://github.com/Triple-T/gradle-play-publisher
