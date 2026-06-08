import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/admin/audit/audit_screen.dart';
import '../features/admin/auth_bindings/auth_bindings_screen.dart';
import '../features/admin/capabilities/capabilities_screen.dart';
import '../features/admin/knowledge_sources/knowledge_sources_screen.dart';
import '../features/admin/operator_auth/operator_auth_screen.dart';
import '../features/admin/profiles/profiles_screen.dart';
import '../features/admin/recording_strategies/recording_strategies_screen.dart';
import '../features/admin/tenants/tenants_screen.dart';
import '../features/admin/tools/tools_screen.dart';
import '../features/admin/tenants/tenants_screen.dart' show tenantsProvider;
import '../features/auth/application/auth_controller.dart';
import '../features/auth/models/auth_state.dart';
import '../features/auth/models/operator.dart';
import '../features/auth/presentation/login_screen.dart';
import '../features/playground/playground_screen.dart';
import '../features/settings/settings_screen.dart';
import 'selected_tenant.dart';

/// GoRouter exposed as a Riverpod provider so the redirect callback can
/// react to operator auth state.
///
/// While auth state is [AuthChecking] the router holds traffic on a
/// /loading splash. Anonymous traffic gets pushed to /login (with a
/// returnTo query param). Authenticated traffic visiting /login is
/// bounced into the app at /playground.
final routerProvider = Provider<GoRouter>((ref) {
  final refresh = _AuthRouterListenable(ref);
  ref.onDispose(refresh.dispose);

  return GoRouter(
    initialLocation: '/playground',
    refreshListenable: refresh,
    redirect: (context, state) {
      final auth = ref.read(authProvider);
      final loc = state.matchedLocation;
      final atLogin = loc == '/login';
      final atLoading = loc == '/loading';

      if (auth is AuthChecking) {
        return atLoading ? null : '/loading';
      }
      if (auth is AuthAuthenticated) {
        return (atLogin || atLoading) ? '/playground' : null;
      }
      // Unauthenticated or SessionExpired → /login (preserve return path).
      if (atLogin) return null;
      final returnTo = (loc.isEmpty || loc == '/loading') ? '/playground' : loc;
      return '/login?returnTo=${Uri.encodeComponent(returnTo)}';
    },
    routes: [
      GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
      GoRoute(path: '/loading', builder: (_, __) => const _BootstrapSplash()),
      ShellRoute(
        builder: (context, state, child) => _AppShell(child: child),
        routes: [
          GoRoute(path: '/playground', builder: (_, __) => const PlaygroundScreen()),
          GoRoute(path: '/admin/tenants', builder: (_, __) => const TenantsScreen()),
          GoRoute(path: '/admin/profiles', builder: (_, __) => const ProfilesScreen()),
          GoRoute(path: '/admin/capabilities', builder: (_, __) => const CapabilitiesScreen()),
          GoRoute(path: '/admin/tools', builder: (_, __) => const ToolsScreen()),
          GoRoute(path: '/admin/knowledge-sources', builder: (_, __) => const KnowledgeSourcesScreen()),
          GoRoute(path: '/admin/auth-bindings', builder: (_, __) => const AuthBindingsScreen()),
          GoRoute(path: '/admin/recording-strategies', builder: (_, __) => const RecordingStrategiesScreen()),
          GoRoute(path: '/admin/audit', builder: (_, __) => const AuditScreen()),
          GoRoute(path: '/admin/operator-auth', builder: (_, __) => const OperatorAuthScreen()),
          GoRoute(path: '/settings', builder: (_, __) => const SettingsScreen()),
        ],
      ),
    ],
  );
});

/// Bridges Riverpod's authProvider into a [Listenable] for GoRouter's
/// refreshListenable so the redirect rebuilds on every auth transition.
class _AuthRouterListenable extends ChangeNotifier {
  _AuthRouterListenable(Ref ref) {
    _sub = ref.listen<AuthState>(authProvider, (_, __) => notifyListeners());
  }
  late final ProviderSubscription<AuthState> _sub;

  @override
  void dispose() {
    _sub.close();
    super.dispose();
  }
}

class _BootstrapSplash extends StatelessWidget {
  const _BootstrapSplash();
  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}

// ---------------------------------------------------------------------------
// App shell — wraps every authenticated route with the nav rail + top bar.
// ---------------------------------------------------------------------------

class _NavItem {
  const _NavItem(this.label, this.icon, this.selectedIcon, this.location);
  final String label;
  final IconData icon;
  final IconData selectedIcon;
  final String location;
}

const _navItems = <_NavItem>[
  _NavItem('Playground', Icons.chat_outlined, Icons.chat, '/playground'),
  _NavItem('Tenants', Icons.apartment_outlined, Icons.apartment, '/admin/tenants'),
  _NavItem('Profiles', Icons.person_outline, Icons.person, '/admin/profiles'),
  _NavItem('Capabilities', Icons.checklist_outlined, Icons.checklist, '/admin/capabilities'),
  _NavItem('Tools', Icons.build_outlined, Icons.build, '/admin/tools'),
  _NavItem('Knowledge', Icons.menu_book_outlined, Icons.menu_book, '/admin/knowledge-sources'),
  _NavItem('Auth bindings', Icons.lock_outlined, Icons.lock, '/admin/auth-bindings'),
  _NavItem('Recording', Icons.fiber_manual_record_outlined, Icons.fiber_manual_record, '/admin/recording-strategies'),
  _NavItem('Audit', Icons.policy_outlined, Icons.policy, '/admin/audit'),
  _NavItem('Operator auth', Icons.admin_panel_settings_outlined, Icons.admin_panel_settings, '/admin/operator-auth'),
  _NavItem('Settings', Icons.tune_outlined, Icons.tune, '/settings'),
];

class _AppShell extends ConsumerWidget {
  const _AppShell({required this.child});
  final Widget child;

  int _selectedIndex(String location) {
    for (var i = 0; i < _navItems.length; i++) {
      if (location.startsWith(_navItems[i].location)) return i;
    }
    return 0;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final location = GoRouterState.of(context).uri.path;
    final selected = _selectedIndex(location);
    final wide = MediaQuery.of(context).size.width > 1100;
    final auth = ref.watch(authProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Yaya Agentic — Admin'),
        actions: [
          if (auth is AuthAuthenticated) const _TenantPicker(),
          if (auth is AuthAuthenticated) _OperatorMenu(operator: auth.operator),
        ],
      ),
      body: Row(
        children: [
          NavigationRail(
            extended: wide,
            selectedIndex: selected,
            onDestinationSelected: (i) => context.go(_navItems[i].location),
            labelType: wide ? NavigationRailLabelType.none : NavigationRailLabelType.all,
            destinations: _navItems
                .map((item) => NavigationRailDestination(
                      icon: Icon(item.icon),
                      selectedIcon: Icon(item.selectedIcon),
                      label: Text(item.label),
                    ))
                .toList(),
          ),
          const VerticalDivider(width: 1),
          Expanded(child: child),
        ],
      ),
    );
  }
}

/// Top-bar tenant selector. Drives every list provider in the admin
/// console + the playground. Hidden until the operator is authenticated
/// (no point selecting a tenant before login). Archived tenants are
/// filtered out — the operator can't accidentally write to an archived
/// row that the backend will reject anyway.
class _TenantPicker extends ConsumerWidget {
  const _TenantPicker();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenants = ref.watch(tenantsProvider);
    final selected = ref.watch(currentTenantOrNull);
    final theme = Theme.of(context);

    return tenants.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(horizontal: 12),
        child: SizedBox.square(
          dimension: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      ),
      error: (_, __) => const SizedBox.shrink(),
      data: (list) {
        final eligible = list.where((t) => t.status != 'ARCHIVED').toList();
        if (eligible.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: TextButton.icon(
              onPressed: () => GoRouter.of(context).go('/admin/tenants'),
              icon: const Icon(Icons.add_business_outlined),
              label: const Text('Register a tenant'),
            ),
          );
        }
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.apartment_outlined,
                  size: 18, color: theme.colorScheme.onSurfaceVariant),
              const SizedBox(width: 6),
              DropdownButtonHideUnderline(
                child: DropdownButton<String>(
                  value: selected,
                  hint: const Text('Select tenant'),
                  borderRadius: BorderRadius.circular(8),
                  items: eligible
                      .map((t) => DropdownMenuItem<String>(
                            value: t.id,
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(t.id),
                                if (t.status != 'ACTIVE') ...[
                                  const SizedBox(width: 6),
                                  Text('(${t.status.toLowerCase()})',
                                      style: TextStyle(
                                          fontSize: 11,
                                          color: theme.colorScheme.outline)),
                                ],
                              ],
                            ),
                          ))
                      .toList(),
                  onChanged: (v) {
                    if (v != null) {
                      ref.read(selectedTenantProvider.notifier).select(v);
                    }
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _OperatorMenu extends ConsumerWidget {
  const _OperatorMenu({required this.operator});
  final Operator operator;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final displayName = operator.displayName;
    final source = operator.source;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: PopupMenuButton<String>(
        tooltip: 'Operator',
        position: PopupMenuPosition.under,
        onSelected: (value) async {
          if (value == 'signout') {
            await ref.read(authProvider.notifier).logout();
          }
        },
        itemBuilder: (_) => [
          PopupMenuItem<String>(
            enabled: false,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(displayName,
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                if (source.isNotEmpty)
                  Text('via $source',
                      style: const TextStyle(fontSize: 12, color: Colors.black54)),
              ],
            ),
          ),
          const PopupMenuDivider(),
          const PopupMenuItem<String>(
              value: 'signout',
              child: ListTile(
                leading: Icon(Icons.logout),
                title: Text('Sign out'),
                dense: true,
                contentPadding: EdgeInsets.zero,
              )),
        ],
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            children: [
              const Icon(Icons.account_circle_outlined),
              const SizedBox(width: 8),
              Text(displayName),
              const Icon(Icons.arrow_drop_down),
            ],
          ),
        ),
      ),
    );
  }
}

