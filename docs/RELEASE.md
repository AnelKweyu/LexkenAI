# Building, distributing and releasing LexKen AI

Four workflows under `.github/workflows/`:

| Workflow | Trigger | Produces |
|---|---|---|
| `ci.yml` | every PR, every push to `main` | debug + R8 builds, unit tests (both flavours), Kover coverage gate, ktlint + detekt, lint, parity-coverage (skips without the appendix) |
| `distribute.yml` | push to `main` | R8 **staging** APK → Firebase App Distribution testers |
| `cd.yml` | tag `v*` or manual run | store-signed **prod** AAB/APK + staging APK → GitHub Release → Play internal |
| `nightly.yml` | 02:00 EAT daily | instrumented tests on a managed emulator |

## Flavours

| Flavour | Application id | Backend | Firebase |
|---|---|---|---|
| `staging` | `com.lexken.lexkenai.staging` | `https://stage.lexken.ai/api/` (override `lexken.staging.apiBase`) | needs its own app entry in project `lexken-ai`; no `google-services.json` yet |
| `prod` | `com.lexken.lexkenai` | `https://lexken.ai/api/` (override `lexken.apiBase`) | `app/src/prod/google-services.json` |

`BuildConfig.API_BASE_URL` and `BuildConfig.WS_BASE_URL` (`https://host/api/` → `wss://host/ws/`) are
generated per flavour. Locally the values come from `local.properties` (`lexken.*` keys, untracked);
in CI from environment variables with the same name upper-cased and dots replaced by underscores.

## Cut a release

```
git checkout main && git pull
git tag v1.0.1
git push origin v1.0.1
```

`versionName` is the tag without `v`; `versionCode` is `100 + run number` (monotonic per repository).
The GitHub Release carries the AAB, both APKs and `SHA256SUMS.txt`. **Actions → CD → Run workflow**
builds the same artifacts for any version without a tag and can push to a chosen Play track.

## Secrets (Settings → Secrets and variables → Actions)

| Secret | Used by | Without it |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64`, `LEXKEN_KEYSTORE_PASSWORD`, `LEXKEN_KEY_ALIAS`, `LEXKEN_KEY_PASSWORD` | cd | artifacts are debug-signed, release is a pre-release, no Play upload |
| `LEXKEN_APIBASE`, `LEXKEN_STAGING_APIBASE` | cd, distribute | code defaults (same hosts) |
| `LEXKEN_VF_APIBASE`, `LEXKEN_VF_CLIENTID`, `LEXKEN_VF_CLIENTSECRET`, `LEXKEN_VF_BYPASSKEY`, `LEXKEN_VF_BUNDLEID` | cd, distribute | builds cannot sign in |
| `LEXKEN_TEST_USER`, `LEXKEN_TEST_PASS` | nightly live checks (future) | live suite skipped |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | distribute | App Distribution upload skipped, APK only attached to the run |
| `FIREBASE_STAGING_APP_ID` | distribute | same |
| `PLAY_SERVICE_ACCOUNT_JSON` | cd | Play job skipped with a notice |

Variable (not secret): `FIREBASE_TESTER_GROUPS`, comma-separated App Distribution group aliases (default `testers`).

## One-time setup still outstanding

1. **Firebase, staging app.** In project `lexken-ai` add an Android app with package
   `com.lexken.lexkenai.staging`, download its `google-services.json` into `app/src/staging/`, and
   copy its App ID (`1:…:android:…`) into the `FIREBASE_STAGING_APP_ID` secret. Add the upload key's
   SHA-256 fingerprint to both app entries.
2. **Firebase, service account.** Google Cloud → IAM → create a service account with the
   *Firebase App Distribution Admin* role, create a JSON key, store it as `FIREBASE_SERVICE_ACCOUNT_JSON`.
   Create a tester group (alias `testers`) in App Distribution.
3. **Play Console.** Create the app for `com.lexken.lexkenai`, enrol in Play App Signing with the
   upload key, and **upload the first AAB by hand** (the API refuses to create a listing). Then create
   a service account with *Release manager* access and store its JSON as `PLAY_SERVICE_ACCOUNT_JSON`.
4. **Branch protection** on `main` once the repository is public or on a paid plan.

## Local store-signed build

```
export LEXKEN_KEYSTORE_PATH=~/.lexken/lexkenai-upload.jks
export LEXKEN_KEYSTORE_PASSWORD=… LEXKEN_KEY_ALIAS=lexkenai-upload LEXKEN_KEY_PASSWORD=…
./gradlew :app:bundleProdRelease
```

The keystore never enters the repository (`*.jks` is ignored). Keep a backup of it and its passwords.
