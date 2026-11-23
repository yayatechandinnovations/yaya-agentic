import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import 'app/router.dart';
import 'app/theme.dart';
import 'ui/theme.dart';

void main() {
  // Switch web URLs from the default hash strategy (`/#/verify?token=…`)
  // to clean paths. The account-lifecycle emails build path-style links
  // (AccountMailLinks → /verify, /reset-password, /accept-invitation);
  // under the hash strategy the SPA never sees the ?token= and the user
  // dead-ends at /login. No-op on non-web targets.
  usePathUrlStrategy();
  runApp(const ProviderScope(child: YayaAgenticWebApp()));
}

class YayaAgenticWebApp extends ConsumerWidget {
  const YayaAgenticWebApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      title: 'Yaya Agentic — Admin',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(),
      darkTheme: buildAppDarkTheme(),
      themeMode: ref.watch(themeModeProvider),
      routerConfig: router,
    );
  }
}
