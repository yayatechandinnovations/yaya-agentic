import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../features/audit/presentation/bloc/audit_bloc.dart';
import '../../features/audit/presentation/pages/audit_page.dart';
import '../../features/auth/presentation/bloc/auth_bloc.dart';
import '../../features/clients/presentation/bloc/clients_bloc.dart';
import '../../features/clients/presentation/pages/clients_page.dart';
import '../../features/groups/presentation/bloc/groups_bloc.dart';
import '../../features/groups/presentation/pages/groups_page.dart';
import '../../features/policies/presentation/bloc/policy_editor_bloc.dart';
import '../../features/policies/presentation/pages/policy_editor_page.dart';
import '../../features/settings/presentation/bloc/settings_bloc.dart';
import '../../features/settings/presentation/pages/settings_page.dart';
import '../../features/users/presentation/bloc/users_bloc.dart';
import '../../features/users/presentation/pages/users_page.dart';
import '../../features/auth/presentation/pages/callback_page.dart';
import '../../features/auth/presentation/pages/login_page.dart';
import '../../features/orgs/presentation/bloc/orgs_bloc.dart';
import '../../features/orgs/presentation/pages/orgs_page.dart';
import '../../features/policies/presentation/bloc/simulator_bloc.dart';
import '../../features/policies/presentation/pages/policy_simulator_page.dart';
import '../../features/roles/presentation/bloc/roles_bloc.dart';
import '../../features/roles/presentation/pages/roles_page.dart';
import '../../features/signup/presentation/bloc/accept_invite_bloc.dart';
import '../../features/signup/presentation/bloc/signup_bloc.dart';
import '../../features/signup/presentation/pages/accept_invite_page.dart';
import '../../features/signup/presentation/pages/signup_page.dart';
import '../di/service_locator.dart';
import 'console_shell.dart';
import 'go_router_refresh_stream.dart';

/// App routing with an auth-driven redirect guard. Each protected route provides
/// its own feature BLoC from the service locator.
class AppRouter {
  static GoRouter build(AuthBloc authBloc) {
    return GoRouter(
      initialLocation: '/orgs',
      refreshListenable: GoRouterRefreshStream(authBloc.stream),
      redirect: (context, state) {
        final authed = authBloc.state.isAuthenticated;
        final loc = state.matchedLocation;
        if (loc == '/callback') return null;             // callback exchanges the code
        // Public onboarding pages — reachable without a session by design.
        const public = {'/signin', '/signup', '/accept-invite'};
        if (!authed && !public.contains(loc)) return '/signin';
        if (authed && loc == '/signin') return '/orgs';
        return null;
      },
      routes: [
        // '/signin' is the SPA sign-in page; the backend's own credential form is
        // at '/login' (kept distinct so a same-origin nginx can route both).
        GoRoute(path: '/signin', builder: (_, __) => const LoginPage()),
        GoRoute(
          path: '/signup',
          builder: (_, __) => BlocProvider(
            create: (_) => sl<SignupBloc>(),
            child: const SignupPage(),
          ),
        ),
        GoRoute(
          path: '/accept-invite',
          builder: (_, state) => BlocProvider(
            create: (_) => sl<AcceptInviteBloc>(),
            child: AcceptInvitePage(token: state.uri.queryParameters['token']),
          ),
        ),
        GoRoute(
          path: '/callback',
          builder: (_, state) => CallbackPage(code: state.uri.queryParameters['code']),
        ),
        ShellRoute(
          builder: (context, state, child) =>
              ConsoleShell(location: state.matchedLocation, child: child),
          routes: [
            GoRoute(
              path: '/orgs',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<OrgsBloc>()..add(const OrgsRequested()),
                child: const OrgsPage(),
              ),
            ),
            GoRoute(
              path: '/users',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<UsersBloc>(),
                child: const UsersPage(),
              ),
            ),
            GoRoute(
              path: '/roles',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<RolesBloc>(),
                child: const RolesPage(),
              ),
            ),
            GoRoute(
              path: '/groups',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<GroupsBloc>(),
                child: const GroupsPage(),
              ),
            ),
            GoRoute(
              path: '/policies',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<PolicyEditorBloc>(),
                child: const PolicyEditorPage(),
              ),
            ),
            GoRoute(
              path: '/clients',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<ClientsBloc>()..add(const ClientsRequested()),
                child: const ClientsPage(),
              ),
            ),
            GoRoute(
              path: '/settings',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<SettingsBloc>(),
                child: const SettingsPage(),
              ),
            ),
            GoRoute(
              path: '/simulator',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<SimulatorBloc>(),
                child: const PolicySimulatorPage(),
              ),
            ),
            GoRoute(
              path: '/audit',
              builder: (_, __) => BlocProvider(
                create: (_) => sl<AuditBloc>(),
                child: const AuditPage(),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
