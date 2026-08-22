# Chat Login — "OTP every time instead of PIN": Root Cause & Fix

> **Status:** client fix implemented (§4.2) — see Implementation status below; §4.3 is a deployment action
> **Scope:** end-user chat app (`yayaagenticchat`) + minor backend hardening
> **Related:** `chat-experience-design.md` §5 (Device PIN unlock & real logout), esp. §5.4 (periodic OTP step-up) and §5.5 (client changes)

## 0. Implementation status

- **§4.2 (client persistence) — DONE.** `credential_store.dart` now uses prefs
  (localStorage) as the durable source of truth on web, with a one-time
  best-effort migration of any secret still in the pre-fix secure-storage
  backend; native keeps Keychain/Keystore unchanged. Guarded by
  `test/credential_store_web_test.dart` (browser) + the existing native
  fallback + startup-gate tests (all green).
- **§4.2.3 (silent self-heal) — already present.** `providers.dart :: _onStartup`
  already re-enrolls (no SMS) whenever the pass is still valid but the secret is
  missing; verified the backend `/devices/enroll` keys the row by the pass's
  `deviceId`, which is the same id `pin-unlock` resolves by. No code change
  needed.
- **§4.3 (backend) — ACTION REQUIRED (deployment, not code).** Set
  `YAYA_CHAT_DEVICE_PASS_SECRET` in every environment (defaults to empty in
  `application.yml`; an empty key makes passes ephemeral per boot, forcing
  re-auth on every restart).

## 1. Symptom

Every login sends an SMS verification code (OTP). The PIN unlock screen is
never offered. Expected behaviour (per `chat-experience-design.md` §5.4): an
**enrolled** device rides the PIN and only steps up to OTP when the device is
**unknown/not enrolled** or its last OTP is **older than 30 days**
(`yaya.agentic.chat.pin-reverify-days`, default 30).

## 2. How login is *supposed* to choose PIN vs OTP

The credential is split in two (§5.2):

| Piece | What it proves | Where it lives (client) | Where it lives (server) |
|---|---|---|---|
| `deviceSecret` (32 bytes, once) | "this is an enrolled device" | **secure storage** (`CredentialStore`) | hashed in `chat_account_devices` |
| `devicePass` (short-lived JWT) | "this device has a live session" | secure storage | signed, stateless |
| `deviceId` (random 16 bytes) | correlation only, *not* trusted | **SharedPreferences** | — |
| PIN | the person | never stored client-side | `chat_accounts.pin_hash` |

Two decision layers:

**A. Client startup gate** — `providers.dart :: _initialState` (lines 124-137) and the shell gate `app/app.dart` (lines 63-90):

```dart
final passValid = !isJwtExpired(creds.devicePass);
final enrolled  = creds.deviceSecret != null && creds.deviceSecret!.isNotEmpty;
locked: !passValid && enrolled,      // ← the whole game
```
- `passValid` → go straight in.
- `!passValid && enrolled` → **`locked` → LockScreen (PIN)**.
- otherwise → **OnboardingScreen → `verifyStart` → OTP**.

**B. Backend step-up** — `ChatVerifyController :: pinUnlock` (lines 143-181), reached *only* from the Lock screen:
```java
var device = devices.resolveBySecret(deviceId, deviceSecret).orElseThrow(unlockDenied);
if (devices.isReverifyDue(device)) throw otpRequired();   // the 30-day check
if (account.hasPin() && device.isPinRequired()) pins.verify(...);
// → fresh session pass
```

## 3. Root cause

**The PIN path is gated entirely by client layer A, and it never fires because
`deviceSecret` is not surviving across sessions on web.** With no persisted
secret, `enrolled == false` on every cold start, so the app can never reach the
Lock screen — it falls to onboarding, which sends the OTP. **The backend 30-day
logic in §2.B is correct and is simply never consulted.**

Why the secret is lost, in order of likelihood:

1. **Two different storage backends, and login trust hinges on the fragile one
   on web.** `deviceId` is in SharedPreferences (a plain `localStorage` key that
   reliably persists), while `deviceSecret` is in `flutter_secure_storage`
   (`^9.2.2`), which on web is a *separately-keyed, WebCrypto-encrypted*
   `localStorage` entry. These two stores drift apart: privacy/incognito modes,
   "clear site data", an ITP/eviction sweep, or a regenerated secure-storage
   encryption key can wipe the encrypted secret while the plain `deviceId`
   survives. Secret gone + deviceId present → `enrolled=false` → OTP.

2. **A read/write asymmetry in `CredentialStore` that can silently drop the
   secret even without a full wipe** (`credential_store.dart`):
   - `_write` (used by `saveSecret`) tries secure storage first and only flips
     `_secureAvailable=false` on a *throw* (lines 74-84).
   - `load()` catches a throw and then reads *both* values **from prefs** (lines
     52-57).
   If a write succeeded to secure storage in session 1 but a read *throws* in
   session 2, `load()` looks the secret up in prefs — where it was never
   written — and returns `null`. `enrolled` collapses to false.

Confirmed **not** the cause: `isReverifyDue` (`ChatDeviceService` 115-119). Its
timestamp logic is correct (`last_verified_at` set on enrollment, compared to
`now − 30d`) and it only runs inside `pin-unlock`, which the failing flow never
calls.

Aggravators worth checking in the deployed env (they force *more* re-auth but
aren't the "always OTP" cause on their own):
- **`device-pass-secret` unset** → `DevicePassService` derives a *random*
  signing key per boot; every backend restart invalidates all passes, so clients
  drop to the startup gate more often (and, combined with cause #1, land on OTP).
  Ensure `YAYA_CHAT_DEVICE_PASS_SECRET` is set in prod.
- **PIN never set** (setup is a skippable nudge) → `account.hasPin()` is false,
  so even a reached `pin-unlock` wouldn't ask for a PIN. Verify the account row
  has `pin_hash`.

## 4. Fix

### 4.1 Confirm (fast, before shipping)
Log `enrolled` / `deviceSecret != null` at `_initialState` on the affected web
client at cold start, with the account known-enrolled server-side
(`chat_account_devices` has a row for this `deviceId`). Expected: secret is
null → confirms §3.1/§3.2.

### 4.2 Client — make secret persistence deterministic (primary fix)
`CredentialStore` (`yayaagenticchat/lib/data/credential_store.dart`):

1. **Pick one backend per platform and stick to it.** On web, use one storage
   mechanism as the *source of truth* rather than racing secure storage against
   prefs. Simplest robust option: on web, persist the secret in prefs
   (`localStorage`) explicitly — the design already accepts prefs-grade storage
   on web (secret unlocks only a short-TTL pass; the PIN is server-verified,
   §5.5) — and keep secure storage only for native. This removes the drift in
   §3.1 and the asymmetry in §3.2 entirely.
2. **If secure storage is retained on web, make read and write symmetric:** a
   read that throws must fall back to the *same* key a write falls back to, and
   the store must dual-write (secure + prefs mirror) so a later read can always
   recover the secret. Never resolve `enrolled` from a store that a write may
   have skipped.
3. **Self-heal on the server round-trip we already make.** `_onStartup` already
   has a "lazy migration" branch that calls `enrollDevice()` with a valid pass to
   mint a fresh secret (`providers.dart` 150-163). Extend it: whenever
   `passValid && deviceSecret == null`, re-enroll and persist — a returning user
   whose secret was evicted but whose pass is still live re-enrolls silently,
   with **no SMS**.

### 4.3 Backend — hardening (secondary)
- Set `YAYA_CHAT_DEVICE_PASS_SECRET` in every environment so passes survive
  restarts (`application.yml` :141).
- No change to `isReverifyDue` / the 30-day window — it is correct.

### 4.4 Explicitly out of scope
Weakening the enrollment model (e.g. trusting `deviceId` alone) — that would
undo the §5.2 guarantee that "an attacker without an enrolled device never
reaches PIN verification." The fix is *persistence reliability*, not a trust
relaxation.

## 5. Verification / acceptance
1. **Fresh device:** first login sends OTP, enrolls, offers PIN setup. ✅ (unchanged)
2. **Return within 30 days (same browser):** cold start → **Lock/PIN screen**,
   no SMS. `pinUnlock` succeeds, mints a fresh pass.
3. **Return after pass expiry but secret evicted, pass still live:** silent
   re-enroll in `_onStartup`, no SMS, no OTP.
4. **Force `last_verified_at` > 30 days:** `pin-unlock` returns `otp_required`,
   client routes to SMS re-verify — the *intended* OTP case.
5. **Web reload loop:** repeated reloads never trigger OTP while enrolled.
6. Add a widget/unit test around `CredentialStore` round-trip and
   `_initialState` (`enrolled` true after a save+reload) on web.

## 6. Files to touch
- `yayaagenticchat/lib/data/credential_store.dart` — persistence strategy (§4.2).
- `yayaagenticchat/lib/data/providers.dart` — extend `_onStartup` self-heal (§4.2.3).
- Deployment config — `YAYA_CHAT_DEVICE_PASS_SECRET` (§4.3).
- Tests under `yayaagenticchat/test/`.
