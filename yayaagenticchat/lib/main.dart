import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_web_plugins/url_strategy.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/app.dart';
import 'data/credential_store.dart';
import 'data/providers.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Clean URLs (no `#`) so the QR/share deep link https://chat.yaya.app/e/{handle}
  // routes straight into go_router on web. No-op off web.
  usePathUrlStrategy();
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
      ],
      child: const YayaChatApp(),
    ),
  );
}
