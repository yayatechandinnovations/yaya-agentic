# Yaya Chat (`yayaagenticchat`)

The branded end-user chat app for the yaya-agentic platform: scan a business QR,
pick a tile, and chat — with storefront, receipts/wallet, forum, and content-free
push wake nudges. Ships to web, iOS, and Android from one Flutter codebase.

Stack: `flutter_riverpod` · `go_router` · `dio` (hand-rolled SSE) ·
`flutter_secure_storage` · `firebase_messaging` (push).

## Run (dev)

```bash
flutter pub get
flutter run -d chrome \
  --dart-define=YAYA_API_BASE=http://localhost:8080 \
  --dart-define=SALES_API_BASE_URL=http://localhost:8092
```

Base URLs default to `localhost` when no `--dart-define` is passed, so dev "just
works" against a local backend.

## Test / analyze

```bash
flutter analyze
flutter test --platform chrome   # widget tests run on the web platform
```

## Release builds

Base URLs are compile-time `--dart-define`s. **A release build that still points
at localhost is rejected at startup** (`main()` shows a "misconfigured" screen)
so a dead build can never reach a user. Supply production values via the checked-in
define files:

```bash
flutter build appbundle --release --dart-define-from-file=dart_define/prod.json   # Play
flutter build ipa       --release --dart-define-from-file=dart_define/prod.json   # App Store
```

Edit `dart_define/prod.json` / `staging.json` for the target backend (no secrets
live there). See `docs/design/chat-mobile-publishing-and-push-design.md` for the
full store-submission and signing checklist.

## Push notifications

FCM wake nudges are content-free (the message is drained from `/inbox`, never
carried in the payload) and **degrade to a no-op until Firebase is configured** —
`lib/firebase_options.dart` ships as a guarded placeholder. To turn push on, run
`flutterfire configure` and follow §B.10 of the design doc (APNs key, iOS
capabilities, backend `yaya.agentic.push.fcm.enabled=true`).
