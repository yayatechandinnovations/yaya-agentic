import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../application/auth_controller.dart';
import '../models/auth_state.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _submitting = false;
  String? _localError;

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() {
      _submitting = true;
      _localError = null;
    });
    final ok = await ref.read(authProvider.notifier).login(
          username: _username.text.trim(),
          password: _password.text,
        );
    if (!mounted) return;
    setState(() => _submitting = false);
    if (ok) {
      final returnTo = GoRouterState.of(context).uri.queryParameters['returnTo']
          ?? '/playground';
      // Use go() rather than push so the login screen leaves history.
      context.go(returnTo);
    }
    // On failure the authProvider's state carries the error; the build
    // method below picks it up and renders it.
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);
    final apiError = (auth is AuthUnauthenticated) ? auth.error : null;
    final sessionExpiredBanner = auth is AuthSessionExpired;
    final error = _localError ?? apiError;
    final theme = Theme.of(context);
    final params = GoRouterState.of(context).uri.queryParameters;
    final verifiedBanner = params['verified'] == '1';
    final resetBanner = params['reset'] == '1';

    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 360),
          child: Card(
            margin: const EdgeInsets.all(24),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Form(
                key: _formKey,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text('Yaya Agentic',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.headlineSmall),
                    const SizedBox(height: 4),
                    Text('Admin console',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.bodyMedium),
                    const SizedBox(height: 24),
                    if (verifiedBanner) ...[
                      _LoginBanner(text: 'Email verified — sign in to continue.'),
                      const SizedBox(height: 12),
                    ],
                    if (resetBanner) ...[
                      _LoginBanner(
                          text: 'Password updated — sign in with the new one.'),
                      const SizedBox(height: 12),
                    ],
                    if (sessionExpiredBanner) ...[
                      _LoginBanner(
                          text: 'Your session has expired. Please sign in again.'),
                      const SizedBox(height: 16),
                    ],
                    TextFormField(
                      controller: _username,
                      decoration: const InputDecoration(
                        labelText: 'Username',
                        prefixIcon: Icon(Icons.person_outline),
                      ),
                      textInputAction: TextInputAction.next,
                      autofillHints: const [AutofillHints.username],
                      validator: (v) =>
                          (v == null || v.trim().isEmpty) ? 'Required' : null,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _password,
                      decoration: const InputDecoration(
                        labelText: 'Password',
                        prefixIcon: Icon(Icons.lock_outline),
                      ),
                      obscureText: true,
                      autofillHints: const [AutofillHints.password],
                      onFieldSubmitted: (_) => _submit(),
                      validator: (v) =>
                          (v == null || v.isEmpty) ? 'Required' : null,
                    ),
                    if (error != null) const SizedBox(height: 12),
                    if (error != null)
                      Text(error,
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.error)),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: _submitting ? null : _submit,
                      child: _submitting
                          ? const SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Sign in'),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        TextButton(
                          onPressed: _submitting
                              ? null
                              : () => context.go('/forgot-password'),
                          child: const Text('Forgot password?'),
                        ),
                        TextButton(
                          onPressed:
                              _submitting ? null : () => context.go('/signup'),
                          child: const Text('Create account'),
                        ),
                      ],
                    ),
                    // Distinct from the password row so the resend path is
                    // visible without crowding it. Carries whatever the
                    // user typed in the Username field so /verify/pending
                    // can pre-fill the form.
                    Center(
                      child: TextButton(
                        onPressed: _submitting
                            ? null
                            : () {
                                final typed = _username.text.trim();
                                final target = typed.isEmpty
                                    ? '/verify/pending'
                                    : '/verify/pending?email=${Uri.encodeComponent(typed)}';
                                context.go(target);
                              },
                        child: const Text("Didn't get the verification email?"),
                      ),
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

/// Reusable banner widget for the verified/reset/session-expired callouts
/// at the top of the login form.
class _LoginBanner extends StatelessWidget {
  const _LoginBanner({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.secondaryContainer,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(text,
          style: TextStyle(color: theme.colorScheme.onSecondaryContainer)),
    );
  }
}
