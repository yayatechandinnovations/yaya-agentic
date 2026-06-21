import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../data/auth_api.dart';
import 'password_strength.dart';

/// Step two of the reset flow: token from query string + new password +
/// confirm. Server rotates the hash AND deletes every active session for
/// the user in one transaction (so we redirect to /login regardless of
/// whether anyone's signed in elsewhere). See UI-UX §4.6.
class ResetPasswordScreen extends ConsumerStatefulWidget {
  const ResetPasswordScreen({super.key});

  @override
  ConsumerState<ResetPasswordScreen> createState() =>
      _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends ConsumerState<ResetPasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  bool _submitting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _password.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _submit(String token) async {
    if (_submitting) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!PasswordStrength.of(_password.text).meetsPolicy) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final api = await ref.read(authApiProvider.future);
      await api.confirmPasswordReset(
        token: token,
        newPassword: _password.text,
      );
      if (!mounted) return;
      context.go('/login?reset=1');
    } on AuthApiException catch (e) {
      if (!mounted) return;
      if (e.isExpiredToken) {
        context.go('/forgot-password?expired=1');
        return;
      }
      setState(() {
        _submitting = false;
        _error = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = 'Reset failed: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final token = GoRouterState.of(context).uri.queryParameters['token'];
    final strength = PasswordStrength.of(_password.text);
    final passwordsMatch = _password.text == _confirm.text;
    final canSubmit = !_submitting &&
        token != null &&
        token.isNotEmpty &&
        strength.meetsPolicy &&
        passwordsMatch;

    if (token == null || token.isEmpty) {
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
                    Text('Missing reset token',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.headlineSmall),
                    const SizedBox(height: 12),
                    const Text(
                      "This page expects a one-time reset link from your email. "
                      'Request a new one if your link is broken.',
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: () => context.go('/forgot-password'),
                      child: const Text('Request a new link'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    }

    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 380),
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
                    Text('Choose a new password',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.headlineSmall),
                    const SizedBox(height: 24),
                    TextFormField(
                      controller: _password,
                      decoration: const InputDecoration(
                        labelText: 'New password',
                        prefixIcon: Icon(Icons.lock_outline),
                      ),
                      obscureText: true,
                      autofillHints: const [AutofillHints.newPassword],
                      validator: (v) {
                        if (v == null || v.isEmpty) return 'Required';
                        final s = PasswordStrength.of(v);
                        if (!s.meetsLength) return 'At least 12 characters';
                        if (!s.meetsClasses) {
                          return 'Mix at least 3 of lowercase, uppercase, digit, symbol';
                        }
                        return null;
                      },
                    ),
                    if (_password.text.isNotEmpty) const SizedBox(height: 10),
                    if (_password.text.isNotEmpty)
                      PasswordStrengthMeter(password: _password.text),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _confirm,
                      decoration: const InputDecoration(
                        labelText: 'Confirm new password',
                        prefixIcon: Icon(Icons.lock_outline),
                      ),
                      obscureText: true,
                      onFieldSubmitted: (_) =>
                          canSubmit ? _submit(token) : null,
                      validator: (v) {
                        if (v == null || v.isEmpty) return 'Required';
                        if (v != _password.text) return 'Passwords do not match';
                        return null;
                      },
                    ),
                    if (_error != null) const SizedBox(height: 12),
                    if (_error != null)
                      Text(_error!,
                          style: TextStyle(color: theme.colorScheme.error)),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: canSubmit ? () => _submit(token) : null,
                      child: _submitting
                          ? const SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Update password'),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      'This will sign you out of every device.',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.outline),
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
