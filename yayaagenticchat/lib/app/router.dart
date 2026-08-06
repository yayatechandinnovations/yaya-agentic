import 'package:go_router/go_router.dart';

import '../features/account/account_screen.dart';
import '../features/account/receipts_screen.dart';
import '../features/chat/chat_screen.dart';
import '../features/courier/courier_screen.dart';
import '../features/directory/directory_screen.dart';
import '../features/discover/discover_screen.dart';
import '../features/entry/entry_screen.dart';
import '../features/forum/forum_screen.dart';
import '../features/scan/scan_screen.dart';
import 'shell.dart';

final appRouter = GoRouter(
  initialLocation: '/',
  // Defense in depth for already-shared links: a deep-link base misconfigured
  // without the `/e/` path segment lands the browser on `/e_<handle>`. Handles
  // self-identify by their `e_` prefix (chat-channel-design.md §6), so normalize
  // to the canonical entry route instead of 404-ing a link that's already on a
  // printed QR code.
  redirect: (context, state) {
    final seg = state.uri.path.replaceFirst('/', '');
    if (seg.startsWith('e_') && !seg.contains('/')) return '/e/$seg';
    return null;
  },
  routes: [
    // Tabbed shell (chat-experience-design.md §9): each branch keeps its own
    // navigation stack, so switching tabs never loses scroll/search state.
    StatefulShellRoute.indexedStack(
      builder: (context, state, navigationShell) =>
          AppShell(navigationShell: navigationShell),
      branches: [
        StatefulShellBranch(routes: [
          GoRoute(path: '/', builder: (context, state) => const DirectoryScreen()),
        ]),
        StatefulShellBranch(routes: [
          GoRoute(
              path: '/forum', builder: (context, state) => const ForumScreen()),
        ]),
        StatefulShellBranch(routes: [
          GoRoute(
              path: '/discover',
              builder: (context, state) => const DiscoverScreen()),
        ]),
        StatefulShellBranch(routes: [
          GoRoute(
              path: '/account',
              builder: (context, state) => const AccountScreen(),
              routes: [
                GoRoute(
                    path: 'receipts',
                    builder: (context, state) => const ReceiptsScreen()),
              ]),
        ]),
      ],
    ),
    // Full-screen surfaces — no bottom nav.
    GoRoute(path: '/scan', builder: (context, state) => const ScanScreen()),
    // Courier mode (yaya-sales-design.md §11.5): the fleet's screen inside the
    // shopper app. Deep link /courier?invite=… pre-fills the registration form.
    GoRoute(
      path: '/courier',
      builder: (context, state) =>
          CourierScreen(inviteToken: state.uri.queryParameters['invite']),
    ),
    // The handle the QR/share link encodes — https://chat.yaya.app/e/{handle}
    // and yayachat://e/{handle}. Lands on the confirm card (§11).
    GoRoute(
      path: '/e/:handle',
      builder: (context, state) => EntryScreen(handle: state.pathParameters['handle']!),
    ),
    GoRoute(
      path: '/chat/:handle',
      builder: (context, state) => ChatScreen(handle: state.pathParameters['handle']!),
    ),
  ],
);
