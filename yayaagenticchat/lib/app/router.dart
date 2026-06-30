import 'package:go_router/go_router.dart';

import '../features/chat/chat_screen.dart';
import '../features/directory/directory_screen.dart';
import '../features/entry/entry_screen.dart';
import '../features/scan/scan_screen.dart';

final appRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(path: '/', builder: (context, state) => const DirectoryScreen()),
    GoRoute(path: '/scan', builder: (context, state) => const ScanScreen()),
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
