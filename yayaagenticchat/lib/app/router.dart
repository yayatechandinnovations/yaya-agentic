import 'package:go_router/go_router.dart';

import '../features/chat/chat_screen.dart';
import '../features/directory/directory_screen.dart';
import '../features/scan/scan_screen.dart';

final appRouter = GoRouter(
  initialLocation: '/',
  routes: [
    GoRoute(path: '/', builder: (context, state) => const DirectoryScreen()),
    GoRoute(path: '/scan', builder: (context, state) => const ScanScreen()),
    GoRoute(
      path: '/chat/:handle',
      builder: (context, state) => ChatScreen(handle: state.pathParameters['handle']!),
    ),
  ],
);
