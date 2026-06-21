import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../data/auth_api.dart';

/// Token-bearer landing for /v1/auth/verify. Consumes the token, shows
/// a transient spinner, and routes:
///   * success → /login?verified=1 (login screen renders a green banner)
///   * expired/consumed/unknown → /verify/pending with an error banner
///   * missing token → /login (the entry should always carry a token)
///
/// See docs/specs/user-accounts/UI-UX.md §4.2.
class VerifyEmailLandingScreen extends ConsumerStatefulWidget {
  const VerifyEmailLandingScreen({super.key});

  @override
  ConsumerState<VerifyEmailLandingScreen> createState() =>
      _VerifyEmailLandingScreenState();
}

class _VerifyEmailLandingScreenState
    extends ConsumerState<VerifyEmailLandingScreen> {
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Kick the redeem off exactly once, after the first frame so we can
    // hop to a new route safely.
    if (_started) return;
    _started = true;
    WidgetsBinding.instance.addPostFrameCallback((_) => _verify());
  }

  Future<void> _verify() async {
    final token = GoRouterState.of(context).uri.queryParameters['token'];
    if (token == null || token.isEmpty) {
      context.go('/login');
      return;
    }
    try {
      final api = await ref.read(authApiProvider.future);
      await api.verifyEmail(token);
      if (!mounted) return;
      context.go('/login?verified=1');
    } on AuthApiException catch (e) {
      if (!mounted) return;
      context.go('/verify/pending?expired=${e.isExpiredToken ? 1 : 0}');
    } catch (_) {
      if (!mounted) return;
      context.go('/verify/pending?expired=1');
    }
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Verifying your email…'),
          ],
        ),
      ),
    );
  }
}

/// "Check your inbox" / resend page. Shown after signup AND after a
/// failed verify (with an inline error banner).
///
/// See UI-UX §4.3.
class VerifyEmailPendingScreen extends ConsumerStatefulWidget {
  const VerifyEmailPendingScreen({super.key});

  @override
  ConsumerState<VerifyEmailPendingScreen> createState() =>
      _VerifyEmailPendingScreenState();
}

class _VerifyEmailPendingScreenState
    extends ConsumerState<VerifyEmailPendingScreen> {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = GoRouterState.of(context);
    final email = state.uri.queryParameters['email'];
    final expired = state.uri.queryParameters['expired'] == '1';

    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 380),
          child: Card(
            margin: const EdgeInsets.all(24),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.mark_email_unread_outlined,
                      size: 48, color: theme.colorScheme.primary),
                  const SizedBox(height: 16),
                  Text('Check your inbox',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.headlineSmall),
                  const SizedBox(height: 12),
                  if (expired)
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.errorContainer,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        "That verification link is no longer valid. Sign in to request a new one.",
                        style: TextStyle(
                            color: theme.colorScheme.onErrorContainer),
                      ),
                    ),
                  if (expired) const SizedBox(height: 16),
                  Text(
                    email == null
                        ? 'We sent a verification email. Open it to activate your account.'
                        : 'We sent a verification email to $email.',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 8),
                  Text('The link expires in 24 hours.',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodySmall),
                  const SizedBox(height: 20),
                  FilledButton.tonal(
                    onPressed: () => context.go('/login'),
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
