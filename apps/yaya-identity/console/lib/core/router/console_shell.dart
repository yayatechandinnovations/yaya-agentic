import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/bloc/auth_bloc.dart';
import '../theme/app_theme.dart';

/// The authenticated shell: a brand-navy nav rail (logo on top, sign-out at the
/// bottom) across the primary sections.
class ConsoleShell extends StatelessWidget {
  const ConsoleShell({super.key, required this.child, required this.location});

  final Widget child;
  final String location;

  static const _destinations = [
    ('/orgs', Icons.apartment, 'Orgs'),
    ('/users', Icons.people, 'Users'),
    ('/groups', Icons.groups, 'Groups'),
    ('/roles', Icons.badge, 'Roles'),
    ('/policies', Icons.policy, 'Policies'),
    ('/clients', Icons.smart_toy, 'Clients'),
    ('/simulator', Icons.rule, 'Simulator'),
    ('/audit', Icons.receipt_long, 'Audit'),
    ('/settings', Icons.settings, 'Settings'),
  ];

  int get _selectedIndex {
    final i = _destinations.indexWhere((d) => location.startsWith(d.$1));
    return i < 0 ? 0 : i;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            selectedIndex: _selectedIndex,
            onDestinationSelected: (i) => context.go(_destinations[i].$1),
            labelType: NavigationRailLabelType.all,
            leading: Padding(
              padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 4),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(10),
                // The logo lockup ships on the same navy as the rail, so it
                // blends seamlessly.
                child: Image.asset(AppBrand.logoAsset, width: 68),
              ),
            ),
            trailing: Expanded(
              child: Align(
                alignment: Alignment.bottomCenter,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: IconButton(
                    tooltip: 'Sign out',
                    color: Colors.white70,
                    icon: const Icon(Icons.logout),
                    onPressed: () => context.read<AuthBloc>().add(const AuthLogoutRequested()),
                  ),
                ),
              ),
            ),
            destinations: _destinations
                .map((d) => NavigationRailDestination(icon: Icon(d.$2), label: Text(d.$3)))
                .toList(),
          ),
          Expanded(child: child),
        ],
      ),
    );
  }
}
