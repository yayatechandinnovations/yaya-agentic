# yaya-identity console

The operator admin console for **yaya-identity** — a Flutter web app built with
**clean architecture** and the **BLoC (event → state)** pattern. Deliberately
distinct from the repo's Riverpod apps (this is a separate deployable).

## Architecture

Each feature is three layers, dependencies pointing inward:

```
features/<feature>/
├── domain/         entities · repository interfaces · use-cases   (no Flutter, no Dio)
├── data/           models · datasources (Dio) · repository impls   (implements domain)
└── presentation/   bloc (event/state) · pages                      (depends on domain)
core/               config · network (Dio + auth interceptor) · storage · router · theme · di
```

- **Presentation → domain only.** Pages dispatch events and render state; they never touch Dio or JSON.
- **Use-cases** are one-intention classes the BLoC depends on, wrapping repository interfaces.
- **`core/di/service_locator.dart`** is the single composition root (`get_it`) — the only place implementations meet interfaces.
- **Failures, not exceptions**, cross the repository boundary (`core/error/failure.dart`).

## Features (v1 slice)

- **auth** — OAuth auth-code + PKCE against yaya-identity; token stored, attached by the Dio interceptor. `AuthBloc` owns the session lifecycle; go_router redirects on auth state.
- **orgs** — list + create organizations via the admin API.
- **policies** — the **policy simulator**: dry-run a decision and render the outcome; a DENY shows the user-safe reason and the audit reason in **separate panels** (#3 — never blurred).

The remaining screens from `docs/specs/UI-UX.md` (users, groups, roles, scoped
assignment, policy editor, clients, audit, MFA settings) follow the same
three-layer + BLoC shape — add a feature folder and wire it in the service locator.

## Run

```bash
cd apps/yaya-identity/console
flutter pub get
flutter test        # BLoC unit tests (mocked repositories)
flutter analyze     # clean
flutter run -d chrome \
  --dart-define=IDENTITY_BASE_URL=http://localhost:8090 \
  --dart-define=CONSOLE_CLIENT_ID=yaya-console \
  --dart-define=CONSOLE_REDIRECT_URI=http://localhost:5000/callback
```

The redirect URI must match the console client registered in yaya-identity
(`yaya.identity.bootstrap.console-client.redirect-uri`).

## Testing

BLoC tests (`bloc_test` + `mocktail`) assert the event→state transitions with
mocked repositories and real use-cases — the behavioral contract of each feature.
