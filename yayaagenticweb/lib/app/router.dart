import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../features/admin/audit/audit_screen.dart';
import '../features/admin/auth_bindings/auth_bindings_screen.dart';
import '../features/admin/capabilities/capabilities_screen.dart';
import '../features/admin/profiles/profiles_screen.dart';
import '../features/admin/recording_strategies/recording_strategies_screen.dart';
import '../features/admin/tools/tools_screen.dart';
import '../features/playground/playground_screen.dart';
import '../features/settings/settings_screen.dart';

final appRouter = GoRouter(
  initialLocation: '/playground',
  routes: [
    ShellRoute(
      builder: (context, state, child) => _AppShell(child: child),
      routes: [
        GoRoute(path: '/playground', builder: (_, __) => const PlaygroundScreen()),
        GoRoute(path: '/admin/profiles', builder: (_, __) => const ProfilesScreen()),
        GoRoute(path: '/admin/capabilities', builder: (_, __) => const CapabilitiesScreen()),
        GoRoute(path: '/admin/tools', builder: (_, __) => const ToolsScreen()),
        GoRoute(path: '/admin/auth-bindings', builder: (_, __) => const AuthBindingsScreen()),
        GoRoute(path: '/admin/recording-strategies', builder: (_, __) => const RecordingStrategiesScreen()),
        GoRoute(path: '/admin/audit', builder: (_, __) => const AuditScreen()),
        GoRoute(path: '/settings', builder: (_, __) => const SettingsScreen()),
      ],
    ),
  ],
);

class _NavItem {
  const _NavItem(this.label, this.icon, this.selectedIcon, this.location);
  final String label;
  final IconData icon;
  final IconData selectedIcon;
  final String location;
}

const _navItems = <_NavItem>[
  _NavItem('Playground', Icons.chat_outlined, Icons.chat, '/playground'),
  _NavItem('Profiles', Icons.person_outline, Icons.person, '/admin/profiles'),
  _NavItem('Capabilities', Icons.checklist_outlined, Icons.checklist, '/admin/capabilities'),
  _NavItem('Tools', Icons.build_outlined, Icons.build, '/admin/tools'),
  _NavItem('Auth bindings', Icons.lock_outlined, Icons.lock, '/admin/auth-bindings'),
  _NavItem('Recording', Icons.fiber_manual_record_outlined, Icons.fiber_manual_record, '/admin/recording-strategies'),
  _NavItem('Audit', Icons.policy_outlined, Icons.policy, '/admin/audit'),
  _NavItem('Settings', Icons.tune_outlined, Icons.tune, '/settings'),
];

class _AppShell extends StatelessWidget {
  const _AppShell({required this.child});
  final Widget child;

  int _selectedIndex(String location) {
    for (var i = 0; i < _navItems.length; i++) {
      if (location.startsWith(_navItems[i].location)) return i;
    }
    return 0;
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).uri.path;
    final selected = _selectedIndex(location);
    final wide = MediaQuery.of(context).size.width > 1100;
    return Scaffold(
      appBar: AppBar(title: const Text('Yaya Agentic — Admin')),
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
