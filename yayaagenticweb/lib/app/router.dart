import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/admin/audit/audit_screen.dart';
import '../features/admin/auth_bindings/auth_bindings_screen.dart';
import '../features/admin/auth_resolvers/auth_resolvers_screen.dart';
import '../features/admin/capabilities/capabilities_screen.dart';
import '../features/admin/flows/flows_admin_screen.dart';
import '../features/admin/forum/forum_screen.dart';
import '../features/admin/identity_routing/identity_routing_screen.dart';
import '../features/admin/jwt_secrets/jwt_secrets_screen.dart';
import '../features/admin/knowledge_sources/knowledge_sources_screen.dart';
import '../features/admin/network/network_settings_screen.dart';
import '../features/admin/oidc_issuers/oidc_issuers_screen.dart';
import '../features/admin/operator_auth/operator_auth_screen.dart';
import '../features/admin/profiles/profile_editor_screen.dart';
import '../features/admin/profiles/profiles_screen.dart';
import '../features/admin/receipt_templates/receipt_templates_screen.dart';
import '../features/admin/recording_strategies/recording_strategies_screen.dart';
import '../features/admin/tenants/tenants_screen.dart';
import '../features/admin/tools/tool_editor_screen.dart';
import '../features/admin/tools/tools_screen.dart';
import '../features/admin/chats/chats_admin_screen.dart';
import '../features/admin/system_senders/system_senders_screen.dart';
import '../features/admin/sms/sms_admin_screen.dart';
import '../features/admin/platform_senders/platform_senders_screen.dart';
import '../features/admin/whatsapp_opt_outs/whatsapp_opt_outs_screen.dart';
import '../features/admin/whatsapp_senders/whatsapp_senders_screen.dart';
import '../features/admin/whatsapp_templates/whatsapp_shared_templates_screen.dart';
import '../features/admin/whatsapp_templates/whatsapp_templates_screen.dart';
import '../features/api_keys/presentation/api_keys_screen.dart';
import '../features/auth/application/auth_controller.dart';
import '../features/auth/models/auth_state.dart';
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
import '../features/workspaces/presentation/workspace_list_screen.dart';
import '../features/workspaces/presentation/workspace_settings_screen.dart';
import '../ui/form/unsaved_guard.dart';
import 'shell/app_shell.dart';
import 'shell/nav_model.dart';

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
          location: state.uri.toString(),
          returnTo: state.uri.queryParameters['returnTo'],
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
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/playground', onExit: _guardUnsaved, builder: (_, __) => const PlaygroundScreen()),
          GoRoute(path: '/playground/chat', onExit: _guardUnsaved, builder: (_, __) => const ChatPlaygroundScreen()),
          GoRoute(path: '/playground/whatsapp', onExit: _guardUnsaved, builder: (_, __) => const WhatsAppPlaygroundScreen()),
          GoRoute(path: '/workspaces', onExit: _guardUnsaved, builder: (_, __) => const WorkspaceListScreen()),
          GoRoute(
            path: '/workspaces/:code/settings',
            onExit: _guardUnsaved,
            builder: (_, state) => WorkspaceSettingsScreen(
              code: state.pathParameters['code']!,
            ),
          ),
          GoRoute(path: '/admin/tenants', onExit: _guardUnsaved, builder: (_, __) => const TenantsScreen()),
          GoRoute(path: '/admin/profiles', onExit: _guardUnsaved, builder: (_, __) => const ProfilesScreen()),
          GoRoute(
            path: '/admin/profiles/new',
            onExit: _guardUnsaved,
            builder: (_, state) => ProfileEditorScreen(duplicateOf: state.uri.queryParameters['from']),
          ),
          GoRoute(
            path: '/admin/profiles/:id',
            onExit: _guardUnsaved,
            builder: (_, state) => ProfileEditorScreen(profileId: state.pathParameters['id']),
          ),
          GoRoute(path: '/admin/start-flows', onExit: _guardUnsaved, builder: (_, __) => const StartFlowsAdminScreen()),
          GoRoute(path: '/admin/capabilities', onExit: _guardUnsaved, builder: (_, __) => const CapabilitiesScreen()),
          GoRoute(path: '/admin/tools', onExit: _guardUnsaved, builder: (_, __) => const ToolsScreen()),
          GoRoute(
            path: '/admin/tools/new',
            onExit: _guardUnsaved,
            builder: (_, state) => ToolEditorScreen(duplicateOf: state.uri.queryParameters['from']),
          ),
          GoRoute(
            path: '/admin/tools/:id',
            onExit: _guardUnsaved,
            builder: (_, state) => ToolEditorScreen(toolId: state.pathParameters['id']),
          ),
          GoRoute(path: '/admin/knowledge-sources', onExit: _guardUnsaved, builder: (_, __) => const KnowledgeSourcesScreen()),
          GoRoute(path: '/admin/auth-bindings', onExit: _guardUnsaved, builder: (_, __) => const AuthBindingsScreen()),
          GoRoute(path: '/admin/oidc-issuers', onExit: _guardUnsaved, builder: (_, __) => const OidcIssuersScreen()),
          GoRoute(path: '/admin/jwt-secrets', onExit: _guardUnsaved, builder: (_, __) => const JwtSecretsScreen()),
          GoRoute(path: '/admin/auth-resolvers', onExit: _guardUnsaved, builder: (_, __) => const AuthResolversScreen()),
          GoRoute(path: '/admin/identity-routing', onExit: _guardUnsaved, builder: (_, __) => const IdentityRoutingScreen()),
          GoRoute(path: '/admin/network', onExit: _guardUnsaved, builder: (_, __) => const NetworkSettingsScreen()),
          GoRoute(path: '/admin/whatsapp-senders', onExit: _guardUnsaved, builder: (_, __) => const WhatsAppSendersScreen()),
          GoRoute(path: '/admin/sms', onExit: _guardUnsaved, builder: (_, __) => const SmsAdminScreen()),
          GoRoute(path: '/admin/chats', onExit: _guardUnsaved, builder: (_, __) => const ChatsAdminScreen()),
          GoRoute(path: '/admin/forum', onExit: _guardUnsaved, builder: (_, _) => const ForumScreen()),
          GoRoute(path: '/admin/system-senders', onExit: _guardUnsaved, builder: (_, __) => const SystemSendersScreen()),
          GoRoute(path: '/admin/whatsapp-templates', onExit: _guardUnsaved, builder: (_, __) => const WhatsAppTemplatesScreen()),
          GoRoute(path: '/admin/whatsapp-shared-templates', onExit: _guardUnsaved, builder: (_, _) => const WhatsAppSharedTemplatesScreen()),
          GoRoute(path: '/admin/platform-senders', onExit: _guardUnsaved, builder: (_, _) => const PlatformSendersScreen()),
          GoRoute(path: '/admin/receipt-templates', onExit: _guardUnsaved, builder: (_, _) => const ReceiptTemplatesScreen()),
          GoRoute(path: '/admin/whatsapp-opt-outs', onExit: _guardUnsaved, builder: (_, __) => const WhatsAppOptOutsScreen()),
          GoRoute(path: '/admin/recording-strategies', onExit: _guardUnsaved, builder: (_, __) => const RecordingStrategiesScreen()),
          GoRoute(path: '/admin/audit', onExit: _guardUnsaved, builder: (_, __) => const AuditScreen()),
          GoRoute(path: '/admin/operator-auth', onExit: _guardUnsaved, builder: (_, __) => const OperatorAuthScreen()),
          GoRoute(path: '/settings', onExit: _guardUnsaved, builder: (_, __) => const SettingsScreen()),
          GoRoute(path: '/settings/api-keys', onExit: _guardUnsaved, builder: (_, __) => const ApiKeysScreen()),
        ],
      ),
    ],
  );
});

/// Leaving a route with unsaved edits asks first — covers the nav rail, ⌘K,
/// breadcrumbs and the browser back button alike.
Future<bool> _guardUnsaved(BuildContext context, GoRouterState state) =>
    UnsavedChanges.instance.confirmLeave(context);

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
  String? location,
  String? returnTo,
}) {
  final loc = matchedLocation;
  // Where a reload started, carried through /loading so a refresh on
  // /admin/tools/x lands back on it instead of the playground.
  final safeReturn = returnTo != null && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : null;
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
    if (atLoading) return null;
    final here = location ?? loc;
    return here == '/playground' || here.isEmpty ? '/loading' : '/loading?returnTo=${Uri.encodeComponent(here)}';
  }
  if (auth is AuthAuthenticated) {
    // Signed-in users hitting /login bounce into the app; the other
    // public routes (verify landing for someone juggling tabs, etc.)
    // pass through unchanged.
    if (atLoading) return safeReturn ?? '/playground';
    if (atLogin) return '/playground';
    // Platform-only routes (cross-tenant admin, operator-auth config)
    // require the platform-admin flag — non-platform admins land on
    // /playground instead of a screen the API would 403 anyway.
    if (platformOnlyRoutes.contains(loc) && !auth.operator.platformAdmin) {
      return '/playground';
    }
    return null;
  }
  // Unauthenticated or SessionExpired → /login (preserve return path),
  // unless they're on a public route.
  if (isPublic) return null;
  final target = loc == '/loading' ? (safeReturn ?? '/playground') : (loc.isEmpty ? '/playground' : (location ?? loc));
  return '/login?returnTo=${Uri.encodeComponent(target)}';
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
