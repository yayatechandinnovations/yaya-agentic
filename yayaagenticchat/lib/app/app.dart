import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/providers.dart';
import '../data/push_service.dart';
import '../features/courier/courier_screen.dart';
import '../features/lock/lock_screen.dart';
import '../features/lock/pin_setup_screen.dart';
import '../features/onboarding/onboarding_screen.dart';
import '../l10n/app_localizations.dart';
import 'router.dart';
import 'theme.dart';

/// Gates the app behind first-run phone verification (chat-channel-design.md §20)
/// and, once enrolled, behind the device PIN lock (chat-experience-design.md §5):
///
///   unverified            → onboarding (phone → name → OTP)
///   verified + locked     → PIN lock screen (or silent unlock on trusted devices)
///   verified + no PIN yet → one-shot PIN-setup nudge (skippable)
///   verified              → the full routed app
///
/// Exception: `/courier` (yaya-sales-design.md §11.5). A courier is a
/// different persona with its own trust path (invite → OTP → yaya-sales agent
/// token) — the shopper phone gate would block "open a link, register" for
/// someone who never shops here, so a courier deep link renders courier mode
/// directly.
class YayaChatApp extends ConsumerStatefulWidget {
  const YayaChatApp({super.key});

  @override
  ConsumerState<YayaChatApp> createState() => _YayaChatAppState();
}

class _YayaChatAppState extends ConsumerState<YayaChatApp> {
  @override
  void initState() {
    super.initState();
    // Already-verified cold start: listeners below only fire on change, so kick
    // registration once after the first frame. All calls no-op unless Firebase
    // is configured and the user is verified with at least one tile.
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncPush());
  }

  void _syncPush() {
    if (!ref.read(verificationProvider).verified) return;
    final push = ref.read(pushServiceProvider);
    unawaited(push.start());
    unawaited(push.syncRegistration());
  }

  @override
  Widget build(BuildContext context) {
    // Register when the user becomes verified, and re-sync when their tile set
    // changes (a first tile is what makes registration meaningful).
    ref.listen(verificationProvider.select((s) => s.verified), (_, verified) {
      if (verified) _syncPush();
    });
    ref.listen(tilesProvider, (_, _) => _syncPush());

    final v = ref.watch(verificationProvider);

    Widget? gate;
    if (v.locked) {
      // A trusted device (pinRequired off) auto-unlocks in the notifier; show a
      // splash rather than flashing the PIN pad at it.
      gate = v.pinRequired ? const LockScreen() : const _UnlockSplash();
    } else if (!v.verified) {
      final courierEntry = kIsWeb && Uri.base.path.startsWith('/courier');
      gate = courierEntry
          ? CourierScreen(inviteToken: Uri.base.queryParameters['invite'])
          : const OnboardingScreen();
    } else if (v.pendingPinSetup) {
      gate = const PinSetupScreen();
    }

    if (gate != null) {
      return MaterialApp(
        onGenerateTitle: (ctx) => AppL10n.of(ctx).appTitle,
        debugShowCheckedModeBanner: false,
        theme: buildBrandTheme(),
        darkTheme: buildBrandThemeDark(),
        themeMode: ThemeMode.system,
        localizationsDelegates: AppL10n.localizationsDelegates,
        supportedLocales: AppL10n.supportedLocales,
        home: gate,
      );
    }
    return MaterialApp.router(
      onGenerateTitle: (ctx) => AppL10n.of(ctx).appTitle,
      debugShowCheckedModeBanner: false,
      theme: buildBrandTheme(),
      darkTheme: buildBrandThemeDark(),
      themeMode: ThemeMode.system,
      localizationsDelegates: AppL10n.localizationsDelegates,
      supportedLocales: AppL10n.supportedLocales,
      routerConfig: appRouter,
    );
  }
}

class _UnlockSplash extends StatelessWidget {
  const _UnlockSplash();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Image.asset(Brand.logoAsset, width: 96),
            const SizedBox(height: 24),
            const CircularProgressIndicator(),
          ],
        ),
      ),
    );
  }
}
