import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_web_plugins/url_strategy.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/app.dart';
import 'config.dart';
import 'data/credential_store.dart';
import 'data/providers.dart';
import 'data/push_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // A release build that still points the API at localhost is dead on a user's
  // device — fail loudly with a visible screen instead of a broken app that
  // silently 404s every request. (Debug/localhost dev is unaffected.)
  if (kReleaseMode && AppConfig.apiBaseIsLoopback) {
    runApp(const _MisconfiguredApp());
    return;
  }
  // Clean URLs (no `#`) so the QR/share deep link https://chat.yaya.app/e/{handle}
  // routes straight into go_router on web. No-op off web.
  usePathUrlStrategy();
  // Init Firebase before the first frame; degrades to false (inbox polling only)
  // when Firebase isn't configured, so this never blocks or white-screens.
  final pushAvailable = await bootstrapPush();
  final prefs = await SharedPreferences.getInstance();
  // Secrets live in secure storage (chat-experience-design.md §5.5); load once
  // before the first frame so the lock/onboarding decision is synchronous.
  // Nothing at startup may white-screen the app: the store itself degrades to
  // prefs, and this belt-and-braces catch covers anything unforeseen.
  final credentialStore = CredentialStore();
  Credentials credentials;
  try {
    credentials = await credentialStore.load(prefs);
  } catch (_) {
    credentials = Credentials.empty;
  }
  runApp(
    ProviderScope(
      overrides: [
        sharedPrefsProvider.overrideWithValue(prefs),
        initialCredentialsProvider.overrideWithValue(credentials),
        credentialStoreProvider.overrideWithValue(credentialStore),
        pushAvailableProvider.overrideWithValue(pushAvailable),
      ],
      child: const YayaChatApp(),
    ),
  );
}

/// Shown only when a release binary was built without a production API base
/// (`--dart-define=YAYA_API_BASE=...`). Better a clear message than a silently
/// broken app pointing at a dev machine.
class _MisconfiguredApp extends StatelessWidget {
  const _MisconfiguredApp();

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        backgroundColor: Color(0xFF0E0B1A),
        body: Center(
          child: Padding(
            padding: EdgeInsets.all(32),
            child: Text(
              'This build is misconfigured (no production server set). '
              'Please update the app.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white70, fontSize: 16),
            ),
          ),
        ),
      ),
    );
  }
}
