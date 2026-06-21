import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../workspaces/application/workspaces_controller.dart';
import '../application/auth_controller.dart';
import '../data/auth_api.dart';
import '../models/auth_state.dart';

/// Token-bearer landing for `GET /v1/auth/invitations/accept`. Public
/// route — the recipient may not yet have an account. The screen fires
/// the call on first build, then renders one of four states:
///
///   * Accepted (signed-in): "Joined Globex" + Accept-and-switch /
///     Accept-and-stay buttons
///   * NeedsSignup (anon): "You've been invited" + redirect to /signup
///     with the email pre-filled and the token preserved
///   * Expired / Invalid: error banner with "request a new invite" CTA
///
/// See UI-UX §4.7.
class AcceptInvitationScreen extends ConsumerStatefulWidget {
  const AcceptInvitationScreen({super.key});

  @override
  ConsumerState<AcceptInvitationScreen> createState() =>
      _AcceptInvitationScreenState();
}

class _AcceptInvitationScreenState
    extends ConsumerState<AcceptInvitationScreen> {
  bool _started = false;
  bool _loading = true;
  AcceptInvitationResult? _result;
  String? _networkError;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    WidgetsBinding.instance.addPostFrameCallback((_) => _resolve());
  }

  Future<void> _resolve() async {
    final token = GoRouterState.of(context).uri.queryParameters['token'];
    if (token == null || token.isEmpty) {
      setState(() {
        _loading = false;
        _result = const AcceptInvitationInvalid();
      });
      return;
    }
    try {
      final api = await ref.read(authApiProvider.future);
      final res = await api.acceptInvitation(token);
      if (!mounted) return;

      // If anon visitor with a NeedsSignup outcome: bounce to /signup
      // with the email + token carried in query params. The verify-email
      // path will auto-redeem any pending invitation matching the email,
      // so the carry is informational (the signup banner reads it).
      if (res is AcceptInvitationNeedsSignup) {
        context.go(
          '/signup?invite=${Uri.encodeComponent(res.rawToken)}'
          '&email=${Uri.encodeComponent(res.email)}'
          '&workspace=${Uri.encodeComponent(res.workspaceCode)}'
          '${res.role != null ? '&role=${Uri.encodeComponent(res.role!)}' : ''}',
        );
        return;
      }
      setState(() {
        _loading = false;
        _result = res;
      });
    } on AuthApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _networkError = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _networkError = 'Could not accept invitation: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('Checking your invitation…'),
            ],
          ),
        ),
      );
    }
    if (_networkError != null) {
      return _ErrorCard(message: _networkError!, onBack: () => context.go('/login'));
    }
    final r = _result;
    return switch (r) {
      AcceptInvitationAccepted() => _AcceptedCard(result: r),
      AcceptInvitationExpired() => _ErrorCard(
          message:
              'This invitation has expired. Ask the workspace admin to send a new one.',
          onBack: () => context.go('/login'),
        ),
      AcceptInvitationInvalid() => _ErrorCard(
          message:
              "This invitation link is no longer valid. It may have been revoked or already accepted.",
          onBack: () => context.go('/login'),
        ),
      // NeedsSignup never lands here — we bounced to /signup above.
      AcceptInvitationNeedsSignup() ||
      null =>
        _ErrorCard(
            message: 'Unexpected state — please contact support.',
            onBack: () => context.go('/login')),
    };
  }
}

class _AcceptedCard extends ConsumerWidget {
  const _AcceptedCard({required this.result});
  final AcceptInvitationAccepted result;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final auth = ref.watch(authProvider);
    final code = result.workspaceCode ?? '';
    final role = result.role ?? '';
    final signedIn = auth is AuthAuthenticated;

    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Card(
            margin: const EdgeInsets.all(24),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.check_circle,
                      size: 48, color: theme.colorScheme.primary),
                  const SizedBox(height: 12),
                  Text("You've joined the workspace",
                      textAlign: TextAlign.center,
                      style: theme.textTheme.headlineSmall),
                  const SizedBox(height: 8),
                  Text(
                    code.isEmpty
                        ? 'Membership minted successfully.'
                        : 'You now have $role access to "$code".',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 24),
                  if (signedIn)
                    FilledButton.icon(
                      icon: const Icon(Icons.swap_horiz),
                      label: const Text('Switch to this workspace'),
                      onPressed: () async {
                        // Refresh memberships so the new one shows up
                        // immediately, then switch + route.
                        ref.invalidate(myWorkspacesProvider);
                        if (code.isNotEmpty) {
                          await ref
                              .read(currentWorkspaceProvider.notifier)
                              .switchTo(code);
                        }
                        if (context.mounted) context.go('/playground');
                      },
                    ),
                  if (signedIn) const SizedBox(height: 8),
                  if (signedIn)
                    TextButton(
                      onPressed: () {
                        ref.invalidate(myWorkspacesProvider);
                        context.go('/workspaces');
                      },
                      child: const Text('Stay where I am'),
                    )
                  else
                    FilledButton(
                      onPressed: () => context.go('/login'),
                      child: const Text('Sign in to continue'),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ErrorCard extends StatelessWidget {
  const _ErrorCard({required this.message, required this.onBack});
  final String message;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Card(
            margin: const EdgeInsets.all(24),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.error_outline,
                      size: 48, color: theme.colorScheme.error),
                  const SizedBox(height: 12),
                  Text("Couldn't accept invitation",
                      textAlign: TextAlign.center,
                      style: theme.textTheme.headlineSmall),
                  const SizedBox(height: 8),
                  Text(message,
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodyMedium),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: onBack,
                    child: const Text('Back to sign in'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
