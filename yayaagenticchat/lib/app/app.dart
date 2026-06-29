import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/providers.dart';
import '../features/onboarding/onboarding_screen.dart';
import '../l10n/app_localizations.dart';
import 'router.dart';
import 'theme.dart';

/// Gates the app behind first-run phone verification (chat-channel-design.md §20):
/// until the device holds a verified-phone pass, only the onboarding screen is
/// reachable; once verified, the full routed app takes over. Localized en + es.
class YayaChatApp extends ConsumerWidget {
  const YayaChatApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final verified = ref.watch(verificationProvider).verified;
    final theme = buildBrandTheme();

    if (!verified) {
      return MaterialApp(
        onGenerateTitle: (ctx) => AppL10n.of(ctx).appTitle,
        debugShowCheckedModeBanner: false,
        theme: theme,
        localizationsDelegates: AppL10n.localizationsDelegates,
        supportedLocales: AppL10n.supportedLocales,
        home: const OnboardingScreen(),
      );
    }
    return MaterialApp.router(
      onGenerateTitle: (ctx) => AppL10n.of(ctx).appTitle,
      debugShowCheckedModeBanner: false,
      theme: theme,
      localizationsDelegates: AppL10n.localizationsDelegates,
      supportedLocales: AppL10n.supportedLocales,
      routerConfig: appRouter,
    );
  }
}
