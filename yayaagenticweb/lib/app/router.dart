import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/admin/audit/audit_screen.dart';
import '../features/admin/auth_bindings/auth_bindings_screen.dart';
import '../features/admin/auth_resolvers/auth_resolvers_screen.dart';
import '../features/admin/capabilities/capabilities_screen.dart';
import '../features/admin/flows/flows_admin_screen.dart';
import '../features/admin/identity_routing/identity_routing_screen.dart';
import '../features/admin/jwt_secrets/jwt_secrets_screen.dart';
import '../features/admin/knowledge_sources/knowledge_sources_screen.dart';
import '../features/admin/product_catalogs/product_catalogs_screen.dart';
import '../features/admin/oidc_issuers/oidc_issuers_screen.dart';
import '../features/admin/operator_auth/operator_auth_screen.dart';
import '../features/admin/profiles/profiles_screen.dart';
import '../features/admin/recording_strategies/recording_strategies_screen.dart';
import '../features/admin/tenants/tenants_screen.dart';
import '../features/admin/tools/tools_screen.dart';
import '../features/admin/tenants/tenants_screen.dart' show tenantsProvider;
import '../features/admin/chats/chats_admin_screen.dart';
import '../features/admin/sms/sms_admin_screen.dart';
import '../features/admin/whatsapp_opt_outs/whatsapp_opt_outs_screen.dart';
import '../features/admin/whatsapp_senders/whatsapp_senders_screen.dart';
import '../features/admin/whatsapp_templates/whatsapp_templates_screen.dart';
import '../features/api_keys/presentation/api_keys_screen.dart';
import '../features/auth/application/auth_controller.dart';
import '../features/auth/models/auth_state.dart';
import '../features/auth/models/operator.dart';
import '../features/auth/presentation/accept_invitation_screen.dart';
import '../features/auth/presentation/forgot_password_screen.dart';
import '../features/auth/presentation/login_screen.dart';
import '../features/auth/presentation/reset_password_screen.dart';
import '../features/auth/presentation/signup_screen.dart';
import '../features/auth/presentation/verify_email_screen.dart';
import '../features/playground/playground_screen.dart';
import '../features/playground/chat_playground_screen.dart';
import '../features/playground/whatsapp_playground_screen.dart';
import '../features/settings/settings_screen.dart';
import '../features/workspaces/application/workspaces_controller.dart';
import '../features/workspaces/presentation/workspace_list_screen.dart';
import '../features/workspaces/presentation/workspace_settings_screen.dart';
import '../features/workspaces/presentation/workspace_switcher.dart';
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
    redirect: (context, state) => resolveAuthRedirect(
          auth: ref.read(authProvider),
          matchedLocation: state.matchedLocation,
        ),
    routes: [
      GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
      GoRoute(path: '/signup', builder: (_, __) => const SignupScreen()),
      GoRoute(path: '/verify', builder: (_, __) => const VerifyEmailLandingScreen()),
      GoRoute(path: '/verify/pending', builder: (_, __) => const VerifyEmailPendingScreen()),
      GoRoute(path: '/forgot-password', builder: (_, __) => const ForgotPasswordScreen()),
      GoRoute(path: '/reset-password', builder: (_, __) => const ResetPasswordScreen()),
      GoRoute(path: '/accept-invitation', builder: (_, __) => const AcceptInvitationScreen()),
      GoRoute(path: '/loading', builder: (_, __) => const _BootstrapSplash()),
      ShellRoute(
        builder: (context, state, child) => _AppShell(child: child),
        routes: [
          GoRoute(path: '/playground', builder: (_, __) => const PlaygroundScreen()),
          GoRoute(path: '/playground/chat', builder: (_, __) => const ChatPlaygroundScreen()),
          GoRoute(path: '/playground/whatsapp', builder: (_, __) => const WhatsAppPlaygroundScreen()),
          GoRoute(path: '/workspaces', builder: (_, __) => const WorkspaceListScreen()),
          GoRoute(
            path: '/workspaces/:code/settings',
            builder: (_, state) => WorkspaceSettingsScreen(
              code: state.pathParameters['code']!,
            ),
          ),
          GoRoute(path: '/admin/tenants', builder: (_, __) => const TenantsScreen()),
          GoRoute(path: '/admin/profiles', builder: (_, __) => const ProfilesScreen()),
          GoRoute(path: '/admin/start-flows', builder: (_, __) => const StartFlowsAdminScreen()),
          GoRoute(path: '/admin/capabilities', builder: (_, __) => const CapabilitiesScreen()),
          GoRoute(path: '/admin/tools', builder: (_, __) => const ToolsScreen()),
          GoRoute(path: '/admin/knowledge-sources', builder: (_, __) => const KnowledgeSourcesScreen()),
          GoRoute(path: '/admin/product-catalogs', builder: (_, __) => const ProductCatalogsScreen()),
          GoRoute(path: '/admin/auth-bindings', builder: (_, __) => const AuthBindingsScreen()),
          GoRoute(path: '/admin/oidc-issuers', builder: (_, __) => const OidcIssuersScreen()),
          GoRoute(path: '/admin/jwt-secrets', builder: (_, __) => const JwtSecretsScreen()),
          GoRoute(path: '/admin/auth-resolvers', builder: (_, __) => const AuthResolversScreen()),
          GoRoute(path: '/admin/identity-routing', builder: (_, __) => const IdentityRoutingScreen()),
          GoRoute(path: '/admin/whatsapp-senders', builder: (_, __) => const WhatsAppSendersScreen()),
          GoRoute(path: '/admin/sms', builder: (_, __) => const SmsAdminScreen()),
          GoRoute(path: '/admin/chats', builder: (_, __) => const ChatsAdminScreen()),
          GoRoute(path: '/admin/whatsapp-templates', builder: (_, __) => const WhatsAppTemplatesScreen()),
          GoRoute(path: '/admin/whatsapp-opt-outs', builder: (_, __) => const WhatsAppOptOutsScreen()),
          GoRoute(path: '/admin/recording-strategies', builder: (_, __) => const RecordingStrategiesScreen()),
          GoRoute(path: '/admin/audit', builder: (_, __) => const AuditScreen()),
          GoRoute(path: '/admin/operator-auth', builder: (_, __) => const OperatorAuthScreen()),
          GoRoute(path: '/settings', builder: (_, __) => const SettingsScreen()),
          GoRoute(path: '/settings/api-keys', builder: (_, __) => const ApiKeysScreen()),
        ],
      ),
    ],
  );
});

/// Pure redirect logic — kept top-level so it's unit-testable without
/// mounting GoRouter. Returns the path to redirect to, or null to let
/// the navigation proceed as-is.
///
/// The load-bearing branch: token-bearer routes (verify, reset-password,
/// accept-invitation) MUST return null during {@link AuthChecking} so
/// the SPA mounts the landing screen with the `?token=` query intact.
/// Bouncing them to /loading strips the query and dead-ends users at
/// /login when bootstrap completes.
@visibleForTesting
String? resolveAuthRedirect({
  required AuthState auth,
  required String matchedLocation,
}) {
  final loc = matchedLocation;
  final atLogin = loc == '/login';
  final atLoading = loc == '/loading';
  // Public routes — accessible without a session. /verify and
  // /reset-password are token-bearer landings; /signup, /verify/pending,
  // and /forgot-password are self-serve entry/info screens. Anonymous
  // visitors and signed-in users alike can hit any of these.
  final isPublic = atLogin ||
      loc == '/signup' ||
      loc == '/verify' ||
      loc == '/verify/pending' ||
      loc == '/forgot-password' ||
      loc == '/reset-password' ||
      loc == '/accept-invitation';

  if (auth is AuthChecking) {
    // Token-bearer routes must mount immediately — see top-of-function
    // note. The other public routes are cheap to show during the
    // spinner window too.
    if (isPublic) return null;
    return atLoading ? null : '/loading';
  }
  if (auth is AuthAuthenticated) {
    // Signed-in users hitting /login bounce into the app; the other
    // public routes (verify landing for someone juggling tabs, etc.)
    // pass through unchanged.
    if (atLogin || atLoading) return '/playground';
    // Platform-only routes (cross-tenant admin, operator-auth config)
    // require the platform-admin flag — non-platform admins land on
    // /playground instead of a screen the API would 403 anyway.
    if (_platformOnlyRoutes.contains(loc) && !auth.operator.platformAdmin) {
      return '/playground';
    }
    return null;
  }
  // Unauthenticated or SessionExpired → /login (preserve return path),
  // unless they're on a public route.
  if (isPublic) return null;
  final returnTo = (loc.isEmpty || loc == '/loading') ? '/playground' : loc;
  return '/login?returnTo=${Uri.encodeComponent(returnTo)}';
}

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

/// Common surface for both leaf nav items and expandable groups.
sealed class _NavEntry {
  const _NavEntry(this.label, this.icon, this.selectedIcon, {this.platformOnly = false});
  final String label;
  final IconData icon;
  final IconData selectedIcon;

  /// Platform-tier-only entries are filtered out for non-platform-admin users
  /// (the route redirect below also bounces direct URL hits).
  final bool platformOnly;
}

/// A single nav destination. Tapping navigates to {@code location}.
class _NavItem extends _NavEntry {
  const _NavItem(
    super.label,
    super.icon,
    super.selectedIcon,
    this.location, {
    super.platformOnly,
  });
  final String location;
}

/// A collapsible group with a header tile and a list of child destinations.
/// Auto-expanded when one of the children matches the current route. Groups
/// themselves are never platform-only — the platform-only flag lives on the
/// children, since hiding a whole group from non-platform admins is a UX
/// choice we don't currently need (and the redirect already protects routes).
class _NavGroup extends _NavEntry {
  const _NavGroup(
    super.label,
    super.icon,
    super.selectedIcon, {
    required this.children,
  });
  final List<_NavItem> children;
}

// Nav information architecture: related destinations are grouped so the rail
// stays scannable as the surface grows. Groups auto-expand when the current
// route is one of their children (see _SideNav). The platform-only flag lives
// on individual items; the app shell strips them for non-platform admins and
// drops any group left empty — so the "Platform" group simply vanishes for
// regular users.
const _navItems = <_NavEntry>[
  // Daily-use test surfaces.
  _NavGroup('Playground', Icons.science_outlined, Icons.science, children: [
    _NavItem('Chat', Icons.chat_outlined, Icons.chat, '/playground'),
    _NavItem('Chat channel', Icons.storefront_outlined, Icons.storefront, '/playground/chat'),
    _NavItem('WhatsApp', Icons.phone_iphone_outlined, Icons.phone_iphone, '/playground/whatsapp'),
  ]),

  // Bot composition primitives — Personality/Profile/Capability/Tool/Knowledge
  // plus how conversations are recorded (design §5.2–§5.3, §5.8–§5.9).
  _NavGroup('Agent design', Icons.smart_toy_outlined, Icons.smart_toy, children: [
    _NavItem('Profiles', Icons.person_outline, Icons.person, '/admin/profiles'),
    _NavItem('Start flows', Icons.play_circle_outline, Icons.play_circle, '/admin/start-flows'),
    _NavItem('Capabilities', Icons.checklist_outlined, Icons.checklist, '/admin/capabilities'),
    _NavItem('Tools', Icons.build_outlined, Icons.build, '/admin/tools'),
    _NavItem('Knowledge', Icons.menu_book_outlined, Icons.menu_book, '/admin/knowledge-sources'),
    _NavItem('Catalogs', Icons.shopping_bag_outlined, Icons.shopping_bag, '/admin/product-catalogs'),
    _NavItem('Recording', Icons.fiber_manual_record_outlined, Icons.fiber_manual_record, '/admin/recording-strategies'),
  ]),

  // Conversational-plane identity config — who the runtime trusts and how
  // verified claims route to a profile (design §5.4, §5.6).
  _NavGroup('Authentication', Icons.shield_outlined, Icons.shield, children: [
    _NavItem('Auth bindings', Icons.lock_outlined, Icons.lock, '/admin/auth-bindings'),
    _NavItem('OIDC issuers', Icons.vpn_key_outlined, Icons.vpn_key, '/admin/oidc-issuers'),
    _NavItem('JWT secrets', Icons.key_outlined, Icons.key, '/admin/jwt-secrets'),
    _NavItem('Auth resolvers', Icons.fingerprint_outlined, Icons.fingerprint, '/admin/auth-resolvers'),
    _NavItem('Identity routing', Icons.alt_route_outlined, Icons.alt_route, '/admin/identity-routing'),
  ]),

  // Channel surface — WhatsApp is the only BSP today; the group is named
  // generically so SMS / Telegram / Meta-Cloud slot in beside WhatsApp
  // without nav churn.
  _NavGroup('Inbound channels', Icons.inbox_outlined, Icons.inbox, children: [
    _NavItem('WhatsApp senders', Icons.chat_outlined, Icons.chat, '/admin/whatsapp-senders'),
    _NavItem('SMS (A2P 10DLC)', Icons.sms_outlined, Icons.sms, '/admin/sms'),
    _NavItem('Chats', Icons.chat_bubble_outline, Icons.chat_bubble, '/admin/chats'),
    _NavItem('WhatsApp templates', Icons.article_outlined, Icons.article, '/admin/whatsapp-templates'),
    _NavItem('WhatsApp opt-outs', Icons.block_outlined, Icons.block, '/admin/whatsapp-opt-outs'),
  ]),

  // Workspace-scoped operations: the workspaces the operator belongs to, the
  // keys minted against them, and the per-workspace authz audit trail.
  _NavGroup('Workspace', Icons.workspaces_outline, Icons.workspaces, children: [
    _NavItem('Workspaces', Icons.grid_view_outlined, Icons.grid_view, '/workspaces'),
    _NavItem('API keys', Icons.vpn_key_outlined, Icons.vpn_key, '/settings/api-keys'),
    _NavItem('Audit', Icons.policy_outlined, Icons.policy, '/admin/audit'),
  ]),

  // Platform-admin only — the whole group disappears for regular operators
  // once its platform-only children are filtered out.
  _NavGroup('Platform', Icons.admin_panel_settings_outlined, Icons.admin_panel_settings, children: [
    _NavItem('Tenants', Icons.apartment_outlined, Icons.apartment, '/admin/tenants',
        platformOnly: true),
    _NavItem('Operator auth', Icons.security_outlined, Icons.security, '/admin/operator-auth',
        platformOnly: true),
  ]),

  // App-level config (backend base URL) — the conventional bottom item.
  _NavItem('Settings', Icons.tune_outlined, Icons.tune, '/settings'),
];


/// Routes that should bounce back to `/playground` when a non-platform-admin
/// lands on them directly (typed URL, deep link). Mirrors the platformOnly
/// nav items above; kept as a plain set so the redirect lookup is O(1).
const _platformOnlyRoutes = <String>{
  '/admin/tenants',
  '/admin/operator-auth',
};

class _AppShell extends ConsumerWidget {
  const _AppShell({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final location = GoRouterState.of(context).uri.path;
    final wide = MediaQuery.of(context).size.width > 1100;
    final auth = ref.watch(authProvider);

    // Mount the workspace→tenant bridge so currentWorkspace changes cascade
    // through legacy admin feature providers (which still watch
    // currentTenantOrNull). This is the only place in the tree that
    // guarantees a single subscription for the bridge.
    ref.watch(workspaceToTenantBridgeProvider);

    final isPlatformAdmin =
        auth is AuthAuthenticated && auth.operator.platformAdmin;

    // Strip platform-only entries (top-level AND nested) for regular users
    // so the nav doesn't dangle dead links. The redirect below also bounces
    // direct URL hits.
    final visibleEntries = isPlatformAdmin
        ? _navItems
        : _navItems
            .where((e) => !e.platformOnly)
            .map<_NavEntry>((e) {
              if (e is _NavGroup) {
                return _NavGroup(e.label, e.icon, e.selectedIcon,
                    children: e.children
                        .where((c) => !c.platformOnly)
                        .toList());
              }
              return e;
            })
            .where((e) => e is! _NavGroup || e.children.isNotEmpty)
            .toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Yaya Agentic — Admin'),
        actions: [
          if (auth is AuthAuthenticated) const WorkspaceSwitcher(),
          if (isPlatformAdmin) const _TenantPicker(),
          if (auth is AuthAuthenticated) _OperatorMenu(operator: auth.operator),
        ],
      ),
      body: Row(
        children: [
          _SideNav(
            entries: visibleEntries,
            currentLocation: location,
            wide: wide,
            onSelect: context.go,
          ),
          const VerticalDivider(width: 1),
          Expanded(child: child),
        ],
      ),
    );
  }
}

/// Custom side nav supporting both leaf items and expandable groups. Built
/// against the M3 NavigationRail visual language: same widths, same icon
/// sizes, same selected-state pill. Lives in this file because the only
/// caller is {@link _AppShell}.
class _SideNav extends StatefulWidget {
  const _SideNav({
    required this.entries,
    required this.currentLocation,
    required this.wide,
    required this.onSelect,
  });
  final List<_NavEntry> entries;
  final String currentLocation;
  final bool wide;
  final void Function(String location) onSelect;

  @override
  State<_SideNav> createState() => _SideNavState();
}

class _SideNavState extends State<_SideNav> {
  /// Per-group expansion state, keyed by group label. Groups containing the
  /// current route force-expand on mount and whenever the route changes.
  final Set<String> _expanded = <String>{};

  @override
  void initState() {
    super.initState();
    _autoExpandForRoute();
  }

  @override
  void didUpdateWidget(_SideNav old) {
    super.didUpdateWidget(old);
    if (old.currentLocation != widget.currentLocation) {
      _autoExpandForRoute();
    }
  }

  void _autoExpandForRoute() {
    for (final entry in widget.entries) {
      if (entry is _NavGroup &&
          entry.children.any((c) => widget.currentLocation.startsWith(c.location))) {
        _expanded.add(entry.label);
      }
    }
  }

  bool _isSelectedLocation(String loc) =>
      widget.currentLocation.startsWith(loc);

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.wide ? 260 : 80,
      child: Material(
        color: Theme.of(context).colorScheme.surface,
        child: ListView(
          padding: const EdgeInsets.symmetric(vertical: 8),
          children: widget.entries.map(_renderEntry).toList(),
        ),
      ),
    );
  }

  Widget _renderEntry(_NavEntry entry) {
    if (entry is _NavItem) {
      return _navTile(
        label: entry.label,
        icon: entry.icon,
        selectedIcon: entry.selectedIcon,
        selected: _isSelectedLocation(entry.location),
        indent: 0,
        onTap: () => widget.onSelect(entry.location),
      );
    }
    if (entry is _NavGroup) {
      final hasSelectedChild =
          entry.children.any((c) => _isSelectedLocation(c.location));
      final expanded = _expanded.contains(entry.label) || hasSelectedChild;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _navTile(
            label: entry.label,
            icon: entry.icon,
            selectedIcon: entry.selectedIcon,
            selected: hasSelectedChild && !expanded,
            indent: 0,
            trailing: widget.wide
                ? Icon(expanded ? Icons.expand_less : Icons.expand_more)
                : null,
            onTap: () => setState(() {
              if (_expanded.contains(entry.label)) {
                _expanded.remove(entry.label);
              } else {
                _expanded.add(entry.label);
              }
            }),
          ),
          if (expanded)
            ...entry.children.map((c) => _navTile(
                  label: c.label,
                  icon: c.icon,
                  selectedIcon: c.selectedIcon,
                  selected: _isSelectedLocation(c.location),
                  indent: 1,
                  onTap: () => widget.onSelect(c.location),
                )),
        ],
      );
    }
    return const SizedBox.shrink();
  }

  Widget _navTile({
    required String label,
    required IconData icon,
    required IconData selectedIcon,
    required bool selected,
    required int indent,
    required VoidCallback onTap,
    Widget? trailing,
  }) {
    final scheme = Theme.of(context).colorScheme;
    final pill = selected ? scheme.secondaryContainer : Colors.transparent;
    final fg = selected ? scheme.onSecondaryContainer : scheme.onSurfaceVariant;

    final iconWidget = Icon(selected ? selectedIcon : icon, color: fg);

    if (!widget.wide) {
      // Compact mode — icon only, like the M3 NavigationRail unextended look.
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        child: Tooltip(
          message: label,
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: onTap,
            child: Container(
              height: 56,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: pill,
                borderRadius: BorderRadius.circular(16),
              ),
              child: iconWidget,
            ),
          ),
        ),
      );
    }

    return Padding(
      padding: EdgeInsets.fromLTRB(8 + indent * 16.0, 2, 8, 2),
      child: InkWell(
        borderRadius: BorderRadius.circular(28),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: pill,
            borderRadius: BorderRadius.circular(28),
          ),
          child: Row(
            children: [
              iconWidget,
              const SizedBox(width: 14),
              Expanded(
                child: Text(label,
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                          color: fg,
                          fontWeight:
                              selected ? FontWeight.w600 : FontWeight.w500,
                        )),
              ),
              if (trailing != null) IconTheme(data: IconThemeData(color: fg), child: trailing),
            ],
          ),
        ),
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

