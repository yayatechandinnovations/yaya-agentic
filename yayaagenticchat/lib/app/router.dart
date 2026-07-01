import 'package:go_router/go_router.dart';

import '../features/chat/chat_screen.dart';
import '../features/directory/directory_screen.dart';
import '../features/discover/discover_screen.dart';
import '../features/entry/entry_screen.dart';
import '../features/scan/scan_screen.dart';

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
    GoRoute(path: '/', builder: (context, state) => const DirectoryScreen()),
    GoRoute(path: '/scan', builder: (context, state) => const ScanScreen()),
    GoRoute(path: '/discover', builder: (context, state) => const DiscoverScreen()),
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
