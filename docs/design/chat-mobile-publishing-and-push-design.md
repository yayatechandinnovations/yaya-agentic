# yayaagenticchat — App Store / Play Store Publishing & Firebase Push Notifications

**Status:** Proposed · v0.1 · 2026-08-20 · **B1 + B2 + partial Track A implemented 2026-08-20**

> **Implementation note (2026-08-20) — Track A (store hardening), first batch.** Shipped the self-contained, verifiable items; `flutter analyze` clean, 62 Flutter + 814 backend tests green.
> - **A0 release safety:** `AppConfig.isLoopback` + a `main()` guard that shows a "misconfigured" screen instead of booting a release pointed at localhost; `config_release_guard_test.dart`. Checked-in `dart_define/prod.json` + `staging.json` for `--dart-define-from-file`.
> - **A6 web/PWA rebrand:** `web/manifest.json`, `web/index.html`, and `README.md` de-placeholdered ("Yaya Chat").
> - **A5 permission strings:** iOS `NSMicrophoneUsageDescription` + `NSPhotoLibraryUsageDescription` added (camera already present).
> - **A5/1.2 forum block/mute (the hard gate):** members can block a post's author — their posts/replies leave the blocker's feed. Backend: `forum_blocks` (V83) + `ForumBlockEntity`/`ForumBlockRepository`; `ForumService.blockAuthorOfPost`/`unblock`/`listBlocked`/`blockedAccountIds` (rejects self + non-member posts, idempotent); block-aware feed/replies queries; `POST /forum/posts/{postId}/block-author`, `GET /forum/blocks`, `DELETE /forum/blocks/{blockId}` (+4 tests). Client: "Block member" in the post menu, a "Blocked members" manage sheet (list + unblock) on the forum app bar, `ChatApi` block/unblock/list, en+es strings. Blocking by post (never exposing accountId) keeps the account id off the wire.
> **Remaining Track A:** signing/keystore/certs + hosted association files (operator/CI), privacy policy + App Privacy/Data Safety forms, reviewer test-phone, goods classification.

> **Implementation note (2026-08-20) — B1 landed.** Backend FCM transport is in and the full suite is green (810 tests). New in `outreach/push/`: `PushProperties` (`yaya.agentic.push.fcm.*`), `FirebaseConfig` (conditional `FirebaseMessaging` bean, shared project), `FcmPushSender` seam + `FirebaseFcmPushSender` (content-free multicast wake + dead-token pruning on `UNREGISTERED`/`INVALID_ARGUMENT`; resolves `entryPointId`→`handle` so the push can deep-link), and `CompositePushNotifier` — now the **sole** `PushNotifier`, grouping tokens by platform and delegating FCM (`LoggingPushNotifier` retired; its logging fallback lives in the composite). `firebase-admin:9.10.0` added to `pom.xml`. **To enable:** set `yaya.agentic.push.fcm.enabled=true` + credentials (`credentialsBase64` | `credentialsLocation` | ambient `GOOGLE_APPLICATION_CREDENTIALS`); left unset, the composite logs and dev/CI need zero Firebase.
>
> **Bug fixed while wiring B2:** the wake read was `findByTenantIdAndEntryPointIdAndRecipientKey`, but tokens are stored **once per `(recipient_key, token)`** (device-level) — so a user with tiles A+B who registered via A got **no push** for B. Wake now reads `findByTenantIdAndRecipientKey` (recipient-level); the specific tile rides the payload. Added `deleteByRecipientKeyAndToken` + a `DELETE /v1/chat/e/{handle}/push-tokens` unregister endpoint (§B.4).
>
> **Implementation note (2026-08-20) — B2 landed.** Flutter client wired; `flutter analyze` clean, 59 widget tests green. New `lib/data/push_service.dart`: `bootstrapPush()` (guarded `Firebase.initializeApp`, background handler), `PushService` (permission, token register/refresh/unregister, foreground/tap handling), `pushInboxPingProvider` (foreground re-drain signal). `firebase_options.dart` is a **guarded placeholder** (`configured=false`) — until `flutterfire configure` runs, push stays off and the app falls back to inbox polling (so web/dev/`flutter test` need no Firebase). Registration fires reactively (verify + tiles) from `app.dart`; unregister runs from `providers.dart` sign-out/delete **before** creds clear; the visible `ChatScreen` re-drains on a foreground ping; a notification tap routes to `/chat/{handle}` (which drains). `ChatApi.unregisterPushToken` (DELETE) added. Tokens registered as `platform:"FCM"` (firebase_messaging yields FCM tokens on Android/iOS/web alike). **Remaining before push works on a device:** the operator setup in §B.10 (flutterfire configure, APNs key, iOS capabilities).
**Owner:** Platform / Chat app
**Scope:** `yayaagenticchat` (the in-repo end-user chat / storefront Flutter app) + the backend push seam.
**Anchors:** `system-messaging-design.md` §11 (store-and-forward + `PushNotifier`), §14 (Flutter surfaces), §17 (SM-3 milestone), §19 (SPI #14); `chat-experience-design.md` (CX surfaces); `yaya-agentic-design.md` §5.9 (Recording), §12 (Safety). This design **implements SM-3** for native mobile and hardens the app for store submission.

> This is two coordinated tracks that ship together: **Track A — store readiness** (turn the existing multi-platform Flutter build into approvable iOS + Android binaries) and **Track B — Firebase Cloud Messaging** (wake the app when an inbound/system message arrives). They share one release, so they are planned together.

---

## 1. Summary

`yayaagenticchat` is already a real multi-platform Flutter app: `ios/`, `android/`, `web/`, and desktop runners are all scaffolded, with production bundle/app IDs already assigned (`com.yayatechandinnovations.yayaagenticchat`) and launcher icons generated. It is **not** web-only. What stands between it and the stores is **configuration, compliance, and signing** — not a port.

Push is even further along than expected: the backend push seam is scaffolded **end-to-end** and the wake call already fires on every inbound delivery. What is missing is only (a) the real FCM transport behind the existing `PushNotifier` SPI, (b) per-tenant FCM credentials storage, and (c) the Flutter client actually obtaining and registering a device token. No new backend endpoint, table, or trigger point is required.

### 1.1 What already exists (do not rebuild)

**Backend push seam — already wired:**
- `PushNotifier` SPI (`outreach/push/PushNotifier.java`) — `wake(TenantId, entryPointId, recipientKey, title)`, content-free by contract.
- `LoggingPushNotifier` (`outreach/push/LoggingPushNotifier.java`) — the current no-op `@Component`; looks up devices via `ChatPushTokenRepository` and logs. Its Javadoc names FCM/APNs as the intended drop-ins.
- **Trigger already fires:** `DefaultOutreachService.deliver(...)` → `maybeWake(...)` calls `pushNotifier.wake(...)` **after** the `chat_inbox` row is persisted, gated on sender quiet-hours, swallowing failures as non-fatal.
- **Token store already exists:** `chat_push_tokens` (migration `V67`) with columns `tenant_id, entry_point_id, recipient_key, platform, token, endpoint_json, created_at`; the `platform` CHECK already allows `('WEBPUSH','FCM','APNS')`; unique `(recipient_key, token)`.
- **Registration endpoint already exists:** `POST /v1/chat/e/{handle}/push-tokens` (`ChatPushTokenController`), body `{platform, token}`, requires a verified (non-anonymous) principal, keys the token to `recipientKey = ByPrincipal(subject).key()`.
- **Inbox drain already exists:** `GET /v1/chat/e/{handle}/inbox` (`ChatInboxController`) — the source-of-truth pull channel; push only optimizes latency.

**Flutter client — partial:**
- Dormant `ChatApi.registerPushToken(handle, token, {platform='WEBPUSH', bearer})` stub — **currently has no callers.**
- Full phone-OTP identity, PIN unlock, secure-storage credential store, in-app account deletion (`/account/delete/*`), and forum with **report** already implemented.

**Store scaffolding — present but placeholder:**
- Real bundle/app IDs on both platforms; generated launcher icons; iOS `NSCameraUsageDescription` + `ITSAppUsesNonExemptEncryption=false`; Android `INTERNET` permission and App Links intent-filter (`autoVerify`, `https://chat.yaya.app/e/*`) + `yayachat://` scheme.
- **Placeholders remaining:** `web/manifest.json` and `web/index.html` still carry Flutter template strings; `README.md` is the default template; release base URLs default to `localhost`; association files (`assetlinks.json`, AASA) not hosted; iOS associated-domains entitlement not configured; Android release keystore (`key.properties`) not provisioned.

---

## Track A — Store publishing readiness

### A.1 Build configuration & environment

**Problem.** Base URLs are compile-time `String.fromEnvironment` dart-defines that **default to `localhost`** (`AppConfig.apiBase` → `YAYA_API_BASE` = `http://localhost:8080`; `SALES_API_BASE_URL` = `http://localhost:8092`). A store build produced without overrides silently points at localhost and is dead on a user's phone.

**Design.**
- Introduce **build flavors / a define bundle** so release binaries are impossible to ship pointing at localhost:
  - `--dart-define=YAYA_API_BASE=https://<prod-api>` and `--dart-define=SALES_API_BASE_URL=https://<prod-sales>` become **required** for release.
  - Provide a checked-in `dart_define/prod.json` + `dart_define/staging.json` consumed via `--dart-define-from-file`, and wire them into the CI release lanes so no human types the URL.
  - Add a fail-fast assertion in a *profile/release* startup path: if `apiBase` resolves to a loopback/`localhost`/`127.0.0.1` host in release mode, hard-error at boot (debug-only guard, compiled out of debug builds where localhost is legitimate).
- HTTPS only in release: confirm no cleartext endpoints. Android — set `android:usesCleartextTraffic="false"` (or omit; default is false on API 28+) and rely on the prod HTTPS base. iOS ATS stays at defaults (no `NSAllowsArbitraryLoads`).

### A.2 Signing & identifiers

**Android.**
- Provision an upload keystore; populate `android/key.properties` (already git-ignored, already read by `build.gradle.kts` with debug-signing fallback). CI stores the keystore + passwords as secrets.
- Enroll in **Play App Signing** (Google holds the app signing key; we hold the upload key).
- Set `minSdk` per plugin floor (already `21`) and **`targetSdk` to the current Play requirement** (Play enforces "target the latest API level within ~1 year of release" — verify against the submission-date requirement and bump `targetSdk`/`compileSdk` accordingly).
- Version strategy: `pubspec` `version: X.Y.Z+N` → `versionName`/`versionCode`. CI must monotonically increment `+N` (versionCode) per upload.

**iOS.**
- Apple Developer Program membership; create the App ID `com.yayatechandinnovations.yayaagenticchat` in the portal.
- **Enable capabilities on the App ID** (needed by Track B too): **Push Notifications**, **Associated Domains**, **Background Modes → Remote notifications**.
- Signing: prefer **automatic signing** with a distribution cert + App Store provisioning profile managed by Xcode/CI (fastlane match or Xcode Cloud). Set the team, bump `CURRENT_PROJECT_VERSION`/`MARKETING_VERSION` from `pubspec`.
- Confirm the `SceneDelegate`-based lifecycle (already present) plays well with the FCM/APNs delegate wiring in Track B (§B.5).

### A.3 Deep links & associated domains

Both stores and the app's own deep-link UX depend on hosted association files that **do not exist yet**:

| Platform | File | Hosted at | Purpose |
|---|---|---|---|
| Android | `assetlinks.json` | `https://chat.yaya.app/.well-known/assetlinks.json` | Verifies App Links (`autoVerify` intent-filter already declared) — SHA-256 of the **Play-signed** cert |
| iOS | `apple-app-site-association` (no extension, `application/json`, no redirects) | `https://chat.yaya.app/.well-known/apple-app-site-association` | Universal Links |

**Design.**
- Serve both from the `chat.yaya.app` origin (static, cached, correct content-type). `assetlinks.json` must list the **App-Signing** SHA-256 (from Play App Signing), not just the upload cert.
- iOS: add the **Associated Domains** entitlement `applinks:chat.yaya.app` (the code comment already flags this as pending) and, for Track B, `webcredentials` is not required.
- Keep the existing custom schemes (`yayachat://e`) as a fallback but rely on verified https links for store-quality UX.

### A.4 Store metadata & privacy compliance

**Shared prerequisites (both stores):**
- **Privacy policy URL** (public, reachable) — mandatory on both stores. Must disclose: phone number collection (OTP identity), device/push tokens, chat content storage, payment processing via Stripe, media uploads.
- **Terms of Service / EULA** — required because the app has user-generated content (forum) and commerce.
- **Account deletion** — Apple 5.1.1(v) and Play both require in-app deletion *and* a deletion path discoverable from the store listing. ✅ The app already has an OTP-confirmed in-app delete (`/account/delete/*`, `delete_account_dialog.dart`). Also publish a **web deletion URL** for Play's Data Safety "deletion" link.
- Screenshots (per device class), app description, keywords, category, support URL, marketing contact.

**Apple — App Privacy "nutrition label" (App Store Connect):**
- Declare data types: Phone Number (identity), User Content (messages, forum posts, media), Purchases (via Stripe), Identifiers (push/device token), Coarse usage/diagnostics if any. Map each to Linked-to-user / Used-for-tracking (expected: **no tracking** → keep it that way, otherwise ATT is triggered, see A.5).
- `ITSAppUsesNonExemptEncryption=false` already set (standard HTTPS exemption) — keep.

**Google — Data Safety form (Play Console):**
- Mirror the same disclosures; declare encryption-in-transit; declare that data can be deleted on request (link the web deletion URL).
- **Play Data-safety must match runtime behavior** — the push token, phone number, and message content all count as collected/shared data.

**Age rating / content:**
- Complete Apple age-rating questionnaire and Google IARC. UGC forum typically pushes the rating up (user-generated content, unrestricted web is **not** present — good). Ensure the questionnaire answers match the forum's moderation controls (A.5).

### A.5 App-specific policy gates (the real rejection risks)

These are the items most likely to get the app bounced. Address before first submission.

1. **User-generated content moderation (Apple Guideline 1.2, Play UGC policy).** The forum lets verified users post/reply/react. Apple 1.2 requires **all** of: a EULA with a no-tolerance clause for objectionable content, a **report** mechanism (✅ present — `forumReport`), a **block/mute user** mechanism (**❌ missing — must add**), and a means for the operator to act on reports and remove content + ejecting abusive users. **Action:** add client "block/mute user" + confirm a backend moderation/removal path exists and reports are actioned within 24h (state this in the review notes).
2. **Payments classification (Apple Guideline 3.1).** The app embeds `flutter_stripe` + checkout. Apple requires **In-App Purchase** for *digital* goods/content consumed in-app, but **allows external payment (Stripe)** for *physical* goods & real-world services. **Action:** confirm the storefront sells **physical goods / real-world services only**; if any digital/virtual item exists, it must go through IAP or be removed from the iOS build. Document the goods classification in the review notes to preempt rejection. (Play is more permissive but still forbids Play Billing bypass for in-app digital content.)
3. **No gambling/lottery surface in this app.** Confirmed: the chat app contains no lottery/betting UI (the only `smartlottery` reference is a currency-formatting comment). The Corillo/network-play surfaces live elsewhere. **Guardrail:** if network-play/lottery affordances are ever surfaced inside `yayaagenticchat`, both stores' real-money-gaming policies apply (licensing, geo-restriction, 18+, and on iOS often a separate entitlement) — out of scope here, flagged so it is never bundled in by accident.
4. **Permissions rationale.** Camera (QR scan via `mobile_scanner`) — iOS `NSCameraUsageDescription` present ✅; verify Android `CAMERA` permission is merged by the plugin and add a purpose string. Microphone (`record` for voice) — add `NSMicrophoneUsageDescription` + Android `RECORD_AUDIO` with rationale. Photos/files (`file_picker`) — add `NSPhotoLibraryUsageDescription` if the library is accessed. Push (Track B) — iOS notification permission prompt with a pre-permission priming screen (§B.5).
5. **Minimum functionality / login gating (Apple 5.1.1, 2.1).** The app gates behind phone-OTP. Apple dislikes forcing account creation for features that don't need identity. Provide **guest/browse** value where possible (directory/discover already browsable pre-verify?) and supply a reviewer entry path, or the reviewer cannot get past the OTP wall → guaranteed rejection. **Decided (§D.2):** a **static test phone + fixed OTP** — a review-mode fixed code keyed to a dedicated number, documented in review notes and revocable after approval (no full-bypass build).

### A.6 Web / PWA cleanup

Low effort, but visible:
- Rebrand `web/manifest.json` (`name`, `short_name`, `description`, `theme_color` `#0E0B1A`, icons) — currently `yayaagenticchat` / "A new Flutter project."
- Update `web/index.html` `<title>` and meta description.
- Replace the default `README.md`.
- (Optional, enables PWA install + Web Push parity — see §B.9.)

### A.7 Release checklists

**Android (Play) — cut list:**
- [ ] Upload keystore + `key.properties` in CI secrets; Play App Signing enrolled
- [ ] `targetSdk` at current Play floor; `versionCode` auto-increment
- [ ] `assetlinks.json` hosted with App-Signing SHA-256
- [ ] Data Safety form; privacy policy URL; account-deletion web URL
- [ ] Content rating (IARC); UGC block/report in place
- [ ] Prod dart-defines wired; no cleartext
- [ ] Reviewer test phone/OTP path
- [ ] AAB built & internal-testing track validated

**iOS (App Store) — cut list:**
- [ ] App ID with Push / Associated Domains / Background-remote capabilities
- [ ] Distribution cert + provisioning (automatic or match)
- [ ] AASA hosted; associated-domains entitlement `applinks:chat.yaya.app`
- [ ] App Privacy labels; privacy policy + EULA; in-app deletion linked
- [ ] Camera/mic/photos usage strings; push priming
- [ ] Goods classification (physical/services) documented for 3.1
- [ ] UGC block/report + moderation notes for 1.2
- [ ] Reviewer demo credentials in review notes
- [ ] TestFlight build validated

---

## Track B — Firebase push notifications (implements SM-3)

### B.1 Goals & invariants

**Goal.** When an inbound/system message is delivered to a user (POS report, ERP document, agent-mediated ask, or any `outreach` delivery), wake that user's device so they see it within seconds instead of on next app open.

**Load-bearing invariants (inherited from `system-messaging-design.md` §11):**
1. **Content-free wake.** The push payload carries **no message body** — only a generic/attributed title. The `chat_inbox` row remains the single source of truth; the device *drains the inbox* on wake. A stolen/intercepted push must leak nothing.
2. **Push is best-effort, never authoritative.** A dropped or undelivered push is non-fatal — the message is already persisted and delivered on next open (the WhatsApp model). `maybeWake` already swallows failures.
3. **Trigger point is untouched.** We implement a transport behind the existing `PushNotifier` SPI; we do **not** add new call sites in the engine or outreach flow.
4. **Quiet hours honored.** `maybeWake` already gates on `sender.limits().isQuietAt(...)` before calling `wake`.

### B.2 Architecture

```
 inbound delivery (DefaultOutreachService.deliver)
        │  persist chat_inbox row  ── source of truth
        ▼
   maybeWake(...)  ── quiet-hours gate (already present)
        ▼
   PushNotifier.wake(tenant, entryPointId, recipientKey, title)
        │
   ┌────┴───────────────────────────┐   CompositePushNotifier (NEW)
   │ dispatch by chat_push_tokens.platform for (tenant,entryPoint,recipient)
   ▼            ▼                     ▼
 FCM tokens   WEBPUSH tokens        (none) → log
   │            │
 FcmPushNotifier (NEW)   WebPushNotifier (§B.9, later)
   │  Firebase Admin SDK, FCM HTTP v1, per-tenant creds
   ▼
 APNs (iOS) / FCM (Android) / (web) ──► device
        │  content-free data+notification: {type, entryPointId, senderTitle}
        ▼
 Flutter FirebaseMessaging handler
        │  foreground → drain inbox inline
        │  background/terminated → show local notification (title only) ; on tap → drain inbox
        ▼
 GET /v1/chat/e/{handle}/inbox   ── renders the real (persisted) message
```

### B.3 Backend

**B.3.1 `FcmPushNotifier implements PushNotifier` (new, `outreach/push/`).**
- On `wake(...)`: look up device rows via `ChatPushTokenRepository.findByTenantIdAndEntryPointIdAndRecipientKey(...)` (the method `LoggingPushNotifier` already calls), filter `platform = 'FCM'`, and send one FCM message per token via the **Firebase Admin SDK** (`google-firebase-admin`, FCM HTTP v1).
- Message is **content-free**: a `notification` with a generic title (`title` arg = `sender.displayName()`) and body like *"New message"* (or omit body), **plus** a `data` map `{ "type":"outreach_wake", "entryPointId":..., "sender":<title> }` used by the client to route the drain. No inbox body, no subject text.
- **Pruning:** on FCM responses `UNREGISTERED` / `INVALID_ARGUMENT`, delete the offending `chat_push_tokens` row (stale-token GC). Log-and-continue on transient errors; never throw out of `wake` (contract is best-effort).

**B.3.2 `CompositePushNotifier` (new) or make the transport map platform-aware.**
- Register a `@Primary` composite that, per recipient, dispatches FCM-platform tokens to `FcmPushNotifier`, `WEBPUSH` to the future `WebPushNotifier` (§B.9), and falls back to `LoggingPushNotifier` when no transport/creds are configured (keeps local dev and tests working with zero Firebase setup).
- Bean wiring: keep `LoggingPushNotifier` as the dev/test default; activate `FcmPushNotifier` only when FCM creds resolve (mirrors the DB-first + config-fallback pattern below).

**B.3.3 FCM credentials — one shared platform project (DECIDED §D.1).**
- **Decision:** a **single shared Firebase project** for the app binary. The client config (`google-services.json` / `GoogleService-Info.plist`) is not secret and is bundled once; the server authenticates FCM HTTP v1 with **one platform-level service-account JSON**. Tenant isolation stays where it already is — at the `recipientKey` + `entry_point_id` level (a wake only ever targets tokens registered by that tenant's recipients).
- **Credential storage:** platform-level, **config/secret-manager**, not a per-tenant DB table:
  - `yaya.agentic.push.fcm.service-account` (path or base64 of the service-account JSON) + `yaya.agentic.push.fcm.project-id`, sourced from the environment / secret manager (env `YAYA_AGENTIC_PUSH_FCM_*`). No new migration.
  - If the JSON must live in the DB instead of env, reuse the existing **`JwtSecretCipher`** (AES-256-GCM) in a single platform-config row — but env/secret-manager is the default and keeps FCM creds off the tenant tables entirely.
  - Initialize one `FirebaseApp` / `FirebaseMessaging` at startup (or lazily on first `wake`); no per-tenant cache or change-event needed.
- **Deferred (only if a tenant ever needs its own push identity):** the per-tenant path — `tenant_fcm_credentials(tenant_id PK, project_id, secret_ciphertext, secret_iv, …)` encrypted via `JwtSecretCipher`, DB-first + config fallback (the `tenant_service_token_secrets` / `V81` shape), plus a `TenantFcmCredentialsChangedEvent` and admin CRUD. Not built in v1.

**B.3.4 Self-hosting tension (call it out).** The standing "self-hosted, no paid services" constraint cannot be fully met for **native** push: APNs (Apple) and FCM (Google) are the *only* delivery channels to iOS/Android and are operator-owned by definition — there is no self-hosted substitute for a native app. FCM itself is **free**. The self-hosted alternative (**Web Push / VAPID**, §B.9) applies only to the PWA/web surface. Recommendation: FCM for the native store apps (unavoidable, free), Web Push/VAPID for the installed PWA — both behind the same `PushNotifier` SPI.

### B.4 Token lifecycle & registration

No new endpoint — reuse `POST /v1/chat/e/{handle}/push-tokens` with `platform:"FCM"`.

- **Register:** after the user grants notification permission *and* is verified (the endpoint rejects anonymous principals), call the existing (currently caller-less) `ChatApi.registerPushToken(handle, token, platform:'FCM', bearer)` for **each added tile's handle** (§14 "one token per added tile" — matches the `entry_point_id` scoping in the table). Wire this into the tile-add flow and app resume.
- **Refresh:** subscribe to `FirebaseMessaging.onTokenRefresh` → re-register the new token for all added tiles; the old token GC's naturally via FCM `UNREGISTERED` pruning (§B.3.1). Idempotent by the `(recipient_key, token)` unique constraint.
- **Unregister:** on sign-out, PIN-reset-to-`otp_required`, tile removal, and **account deletion**, delete the token(s). Add a `DELETE /v1/chat/e/{handle}/push-tokens` (or extend the account-delete cascade) so a signed-out device stops receiving wakes. Account deletion **must** cascade-delete `chat_push_tokens` for the recipient (privacy).
- **Optional schema enrichment:** use the existing `endpoint_json` column for `{ device_id, app_version, last_seen_at }` to support future targeting/debugging without a new migration.

### B.5 Flutter client

**Packages:** `firebase_core`, `firebase_messaging`, `flutter_local_notifications` (render notifications while foregrounded / control the channel), and rely on `firebase_messaging`'s `requestPermission` (no separate `permission_handler` needed for notifications).

**Setup:**
- `flutterfire configure` → generates `firebase_options.dart`, `android/app/google-services.json`, `ios/Runner/GoogleService-Info.plist`. **Do not commit** the platform credential files to a public repo; inject via CI (they are app-identifying config, and per-tenant projects complicate a single bundled file — see Open Questions).
- `Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform)` in `main()` before `runApp`, wrapped so a Firebase init failure degrades gracefully (app must still boot & work via inbox polling — invariant #2).
- **iOS:** upload the **APNs Auth Key (.p8)** to the Firebase project; enable Push Notifications + Background Modes (remote notifications) capabilities (A.2); set `UNUserNotificationCenter` delegate; register for remote notifications. FCM on iOS rides APNs.

**Permission UX (both platforms; iOS mandatory, Android 13+ mandatory):**
- Show a **pre-permission priming screen** (why: "Get notified when a business or the app sends you a message") *before* the OS prompt, so a decline isn't permanent. Request only after the user has verified and added at least one tile (context makes the grant likely). Respect a decline; re-offer from Account settings.

**Message handling:**
- **Foreground** (`FirebaseMessaging.onMessage`): do **not** rely on the push body; trigger an **inbox drain** for the target `entryPointId` and let the existing inbox/rendering path show the real message (attributed system bubble, already implemented in SM-0 Flutter). Optionally surface an in-app toast.
- **Background / terminated** (`FirebaseMessaging.onBackgroundMessage` + system tray): the OS shows the notification (title-only, content-free). On **tap** (`onMessageOpenedApp` / `getInitialMessage`), route to the tile's chat and **drain the inbox**. Use `flutter_local_notifications` with a dedicated Android notification channel (`messages`) for consistent presentation.
- **Payload contract (client expects):** `data: { type:"outreach_wake", entryPointId, sender }`. Client never trusts a `body` for content; it always re-fetches from `/inbox`.

**Drain-on-resume already specified** (§11 step 2) — keep it as the reliability backstop: on every app foreground, drain the inbox regardless of whether a push arrived. Push only lowers latency.

### B.6 Data model changes

| Change | Detail |
|---|---|
| `chat_push_tokens` (reuse) | already supports `platform='FCM'`; optionally populate `endpoint_json` with device metadata; add `DELETE` unregister path |
| FCM service account (config) | one platform-level credential via `yaya.agentic.push.fcm.*` (env / secret manager) — **no new table** with the shared-project decision |

No new migration, and no change to `chat_inbox`, `outreach_sessions`, or the delivery flow. (Per-tenant `tenant_fcm_credentials` is deferred — §B.3.3.)

### B.7 Payload & security

- **Content-free:** title = sender display name; body generic or omitted; **no** subject/message text/media in the push. Enforced in `FcmPushNotifier` (invariant #1).
- **Token = device identifier → PII.** Encrypt tenant creds at rest; prune stale tokens; cascade-delete on account deletion; never log full tokens.
- **Transport:** FCM v1 over TLS; service-account creds never leave the backend.
- **Quiet hours / frequency:** already enforced upstream in `maybeWake`; SM-3 also calls for frequency guardrails — a per-recipient coalescing window (e.g. suppress repeat wakes within N seconds if the inbox already has an undrained entry) can be added in `FcmPushNotifier` to avoid notification spam on bursty senders.

### B.8 Testing

- **Local/dev & CI:** no Firebase creds → `CompositePushNotifier` falls back to `LoggingPushNotifier`; existing 729-test suite unaffected. Add unit tests for platform dispatch, stale-token pruning, and content-free payload assertions (fail the build if any inbox body leaks into a push).
- **Device:** FCM test send to a registered device; verify content-free tray notification, tap→drain, foreground drain, token refresh re-registration, and unregister-on-logout.
- **iOS APNs:** verify on a real device (simulator cannot receive remote push) via TestFlight.

### B.9 Web Push / VAPID (PWA parity — later, self-hosted)

For the installed-PWA surface (and to honor the self-hosted preference where it *is* possible), add a `WebPushNotifier` behind the same SPI using **VAPID** (no third party): register a service worker `web/firebase-messaging-sw.js`-equivalent (or a plain Web Push SW), store the browser `PushSubscription` as a `WEBPUSH` token (already supported), and send via a self-hosted `web-push` library. This closes the loop for users who "install" the web app instead of downloading from a store. **Deferred** — native FCM first.

---

### B.10 Operator setup checklist (turning push on)

The code is in and degrades to inbox-polling until these one-time steps run. None of them are code changes.

**Firebase project (shared, §D.1):**
1. Create/choose the shared Firebase project; add an **Android app** (`com.yayatechandinnovations.yayaagenticchat`) and an **iOS app** (same bundle id).
2. From `yayaagenticchat/`, run `flutterfire configure` → it overwrites `lib/firebase_options.dart` with real options (flips `configured` to `true`) and drops `android/app/google-services.json` + `ios/Runner/GoogleService-Info.plist`. **Inject these via CI; don't commit** the platform credential files.

**iOS (required for any iOS push):**
3. In the Apple Developer portal, create an **APNs Auth Key (.p8)** and upload it to Firebase → Project Settings → Cloud Messaging.
4. In Xcode, enable capabilities on the Runner target: **Push Notifications** and **Background Modes → Remote notifications** (matches §A.2's App-ID capabilities).

**Android:**
5. `POST_NOTIFICATIONS` (Android 13+) is contributed by the messaging/local-notifications plugins; the runtime prompt is driven by `FirebaseMessaging.requestPermission()`. The local-notification channel (`messages`) and `@mipmap/ic_launcher` icon are already handled in `push_service.dart` + the generated launcher icons.

**Backend:**
6. Set `yaya.agentic.push.fcm.enabled=true` and supply the service account (`credentialsBase64` | `credentialsLocation` | ambient `GOOGLE_APPLICATION_CREDENTIALS`). Same shared project as the client.

**Verify:** register a device (sign in + add a tile → grants permission → token POSTed), send an inbound `outreach` message, confirm a content-free tray notification arrives, tap it → lands on the right tile → the real message renders from `/inbox`.

## C. Milestones

| Phase | Deliverable | Depends on |
|---|---|---|
| **A0 — Store hardening** ✅ | Prod dart-defines; web/README rebrand; iOS permission strings; localhost release-guard | — |
| **A1 — Signing & links** | Android keystore + Play App Signing; iOS certs + capabilities; host `assetlinks.json` + AASA; associated-domains entitlement | A0 |
| **A2 — Compliance** | Privacy policy + EULA + deletion URLs; App Privacy + Data Safety; UGC **block/mute** ✅ + moderation notes; goods classification; reviewer test-credential path | A1 |
| **A3 — Submit** | Internal testing (Play) + TestFlight; store listings; first review submission | A2 |
| **B1 — Backend FCM** ✅ | `CompositePushNotifier` + `FcmPushSender`/`FirebaseFcmPushSender`; `FirebaseConfig`; platform-level creds (§B.3.3); dead-token pruning; DELETE unregister | — (parallel to A) |
| **B2 — Client FCM** ✅ | Firebase init (guarded); permission; token register/refresh/unregister; foreground/tap handling; ping re-drain | B1, A1 (iOS caps) |
| **B3 — Harden** | Frequency coalescing, priming screen, richer content-free payload tests, on-device QA (needs §B.10 setup) | B2 |
| **B4 — Web Push (opt.)** | VAPID `WebPushNotifier` for PWA parity | B3 |

A and B can proceed in parallel; both land in the same store build (B2 needs A1's iOS capabilities).

## D. Decisions & open questions

1. **Firebase topology — DECIDED: one shared project** for the app binary. Bundled client config (not secret), one platform-level server service-account credential, tenant isolation at `recipientKey`/`entry_point_id`. Per-tenant projects deferred (§B.3.3).
2. **Reviewer OTP access — DECIDED: static test phone + fixed OTP** (review-mode fixed code keyed to a dedicated number, documented in review notes, revocable post-approval). No full bypass build. (§A.5 #5)
3. **Goods classification for Apple 3.1** (open) — confirm the storefront is 100% physical goods/real-world services on the iOS build; if any digital item exists, decide IAP vs. removal.
4. **UGC block/mute** (open) — confirm backend support for user block + report actioning SLA before A2 (Apple 1.2 hard gate).
5. **Frequency coalescing policy** (open) — exact window/rules for suppressing burst wakes (B3).

## E. Acceptance criteria

- Release builds cannot ship pointing at localhost (release-guard trips; CI supplies prod defines).
- App Links / Universal Links resolve from `chat.yaya.app` on both platforms.
- App passes Apple review (1.2 UGC controls, 3.1 payments, 5.1.1 deletion + reviewer access) and Play review (Data Safety, target API, UGC).
- An inbound `outreach` delivery to a registered device produces a **content-free** notification within seconds; tapping opens the correct tile and the **real** message renders from `/inbox`; no message body ever appears in the push payload (enforced by test).
- A dropped push is non-fatal: the message still appears on next app open (drain-on-resume).
- Signing out / deleting the account removes the device's push tokens (no further wakes).
- Local dev & CI run with **zero** Firebase configuration (logging fallback).
