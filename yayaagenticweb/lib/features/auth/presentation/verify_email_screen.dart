import 'dart:async';

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

/// "Check your inbox" / resend page. Reached three ways:
///   1. Just after signup — `?email=<addr>` carries the address; CTA is pre-armed.
///   2. After a failed verify — `?expired=1`; banner shows + resend is the next step.
///   3. From the login screen's "Didn't get the email?" link — `?email=<typed>`.
///
/// See UI-UX §4.3 and §S-03.
class VerifyEmailPendingScreen extends ConsumerStatefulWidget {
  const VerifyEmailPendingScreen({super.key});

  @override
  ConsumerState<VerifyEmailPendingScreen> createState() =>
      _VerifyEmailPendingScreenState();
}

class _VerifyEmailPendingScreenState
    extends ConsumerState<VerifyEmailPendingScreen> {
  final _email = TextEditingController();
  bool _submitting = false;
  bool _sent = false;
  int _cooldownRemaining = 0;
  Timer? _cooldownTimer;
  String? _error;
  bool _emailPrefilled = false;

  @override
  void dispose() {
    _email.dispose();
    _cooldownTimer?.cancel();
    super.dispose();
  }

  Future<void> _resend(String email) async {
    if (_submitting || _cooldownRemaining > 0) return;
    final trimmed = email.trim();
    if (trimmed.isEmpty || !trimmed.contains('@')) {
      setState(() => _error = 'Enter the email you signed up with.');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final api = await ref.read(authApiProvider.future);
      await api.resendVerification(trimmed);
      if (!mounted) return;
      // Enumeration-safe success copy — same shape whether the email is
      // known or not. The user always sees "if there's a pending account,
      // a fresh link is on its way."
      setState(() {
        _submitting = false;
        _sent = true;
      });
    } on ResendThrottledException catch (e) {
      if (!mounted) return;
      _startCooldown(e.retryAfterSeconds);
      setState(() {
        _submitting = false;
        _error = 'Too many requests. Try again in ${e.retryAfterSeconds}s.';
      });
    } on AuthApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = 'Could not request a new link: $e';
      });
    }
  }

  void _startCooldown(int seconds) {
    _cooldownTimer?.cancel();
    setState(() => _cooldownRemaining = seconds);
    _cooldownTimer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) {
        t.cancel();
        return;
      }
      setState(() {
        _cooldownRemaining -= 1;
        if (_cooldownRemaining <= 0) {
          _cooldownRemaining = 0;
          t.cancel();
        }
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = GoRouterState.of(context);
    final emailFromUrl = state.uri.queryParameters['email'];
    final expired = state.uri.queryParameters['expired'] == '1';

    if (!_emailPrefilled && emailFromUrl != null && emailFromUrl.isNotEmpty) {
      _email.text = emailFromUrl;
      _emailPrefilled = true;
    }

    final showEmailField = emailFromUrl == null || emailFromUrl.isEmpty;
    final cooldownActive = _cooldownRemaining > 0;
    final canSubmit = !_submitting && !cooldownActive;

    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 380),
          child: SingleChildScrollView(
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
                  if (expired) ...[
                    _Banner(
                      tone: _BannerTone.error,
                      text:
                          'That verification link is no longer valid. Request a new one below.',
                    ),
                    const SizedBox(height: 16),
                  ],
                  if (_sent) ...[
                    _Banner(
                      tone: _BannerTone.success,
                      text:
                          "If an account is awaiting verification for that email, "
                          "a new link is on the way. Open the most recent email "
                          "we sent to activate your account.",
                    ),
                    const SizedBox(height: 16),
                  ],
                  if (!_sent)
                    Text(
                      emailFromUrl == null || emailFromUrl.isEmpty
                          ? 'Enter the email you signed up with and we’ll send a new verification link.'
                          : 'We sent a verification email to $emailFromUrl. '
                              'Didn’t get it? Send a new link.',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodyMedium,
                    ),
                  if (!_sent) const SizedBox(height: 8),
                  if (!_sent)
                    Text('Links expire 24 hours after they’re sent.',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodySmall),
                  const SizedBox(height: 20),
                  if (showEmailField && !_sent) ...[
                    TextField(
                      controller: _email,
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [
                        AutofillHints.email,
                        AutofillHints.username,
                      ],
                      decoration: const InputDecoration(
                        labelText: 'Email',
                        prefixIcon: Icon(Icons.email_outlined),
                      ),
                      onSubmitted: canSubmit ? _resend : null,
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (_error != null) ...[
                    Text(_error!,
                        textAlign: TextAlign.center,
                        style: TextStyle(color: theme.colorScheme.error)),
                    const SizedBox(height: 12),
                  ],
                  if (!_sent)
                    FilledButton.icon(
                      onPressed: canSubmit ? () => _resend(_email.text) : null,
                      icon: _submitting
                          ? const SizedBox(
                              height: 16,
                              width: 16,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.refresh),
                      label: Text(cooldownActive
                          ? 'Resend in ${_cooldownRemaining}s'
                          : 'Resend verification email'),
                    ),
                    if (!_sent) const SizedBox(height: 8),
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
      ),
    );
  }
}

enum _BannerTone { error, success }

class _Banner extends StatelessWidget {
  const _Banner({required this.tone, required this.text});
  final _BannerTone tone;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isError = tone == _BannerTone.error;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: isError
            ? theme.colorScheme.errorContainer
            : theme.colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        text,
        style: TextStyle(
            color: isError
                ? theme.colorScheme.onErrorContainer
                : theme.colorScheme.onSecondaryContainer),
      ),
    );
  }
}
