import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../data/auth_api.dart';
import 'password_strength.dart';

/// Self-serve signup. Always 202 — the UI never reveals whether the
/// email is already registered. On success the user is routed to
/// /verify/pending (carrying the email for the resend CTA).
///
/// See docs/specs/user-accounts/UI-UX.md §4.1.
class SignupScreen extends ConsumerStatefulWidget {
  const SignupScreen({super.key});

  @override
  ConsumerState<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends ConsumerState<SignupScreen> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _displayName = TextEditingController();
  final _password = TextEditingController();
  bool _submitting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // Re-render on every keystroke so the password-strength meter and CTA
    // enablement track input live.
    _password.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _email.dispose();
    _displayName.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) return;
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!PasswordStrength.of(_password.text).meetsPolicy) return;
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      final api = await ref.read(authApiProvider.future);
      await api.signup(
        email: _email.text.trim(),
        displayName: _displayName.text.trim(),
        password: _password.text,
      );
      if (!mounted) return;
      context.go('/verify/pending?email=${Uri.encodeComponent(_email.text.trim())}');
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
        _error = 'Sign-up failed: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final strength = PasswordStrength.of(_password.text);
    final canSubmit = !_submitting && strength.meetsPolicy;

    // Invite-carry: when the user arrives via /accept-invitation →
    // /signup?invite=…&email=…&workspace=…&role=…, lock the email field
    // to the invited address and surface a banner. Verification will
    // auto-redeem any pending invitation matching the email — the carry
    // token is informational from here (server matches on email).
    final params = GoRouterState.of(context).uri.queryParameters;
    final inviteEmail = params['email'];
    final inviteWorkspace = params['workspace'];
    final inviteRole = params['role'];
    final hasInvite = inviteEmail != null && inviteEmail.isNotEmpty;
    if (hasInvite && _email.text.isEmpty) {
      // One-shot: pre-fill once. We don't keep overwriting on rebuild.
      _email.text = inviteEmail;
    }

    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
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
                    Text(hasInvite
                            ? 'Accept invitation'
                            : 'Create your account',
                        textAlign: TextAlign.center,
                        style: theme.textTheme.headlineSmall),
                    const SizedBox(height: 16),
                    if (hasInvite) ...[
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: theme.colorScheme.secondaryContainer,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Row(
                          children: [
                            Icon(Icons.mail_outline,
                                size: 18,
                                color: theme.colorScheme.onSecondaryContainer),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                inviteWorkspace == null
                                    ? "You've been invited to join a workspace. Create your account to accept."
                                    : "You've been invited to join "
                                        "\"$inviteWorkspace\""
                                        "${inviteRole != null ? " as $inviteRole" : ""}."
                                        " Create your account to accept.",
                                style: TextStyle(
                                    color: theme.colorScheme.onSecondaryContainer,
                                    fontSize: 13),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),
                    ],
                    TextFormField(
                      controller: _email,
                      readOnly: hasInvite,
                      decoration: InputDecoration(
                        labelText: 'Email',
                        prefixIcon: const Icon(Icons.email_outlined),
                        helperText: hasInvite
                            ? 'Locked to the invited address.'
                            : null,
                      ),
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.next,
                      autofillHints: const [AutofillHints.email, AutofillHints.username],
                      validator: (v) {
                        final s = v?.trim() ?? '';
                        if (s.isEmpty) return 'Required';
                        // Loose RFC-shape check; server is authoritative.
                        if (!s.contains('@') || !s.contains('.')) return 'Invalid email';
                        return null;
                      },
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _displayName,
                      decoration: const InputDecoration(
                        labelText: 'Display name',
                        prefixIcon: Icon(Icons.badge_outlined),
                      ),
                      textInputAction: TextInputAction.next,
                      autofillHints: const [AutofillHints.name],
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
                      autofillHints: const [AutofillHints.newPassword],
                      onFieldSubmitted: (_) => canSubmit ? _submit() : null,
                      validator: (v) {
                        if (v == null || v.isEmpty) return 'Required';
                        final s = PasswordStrength.of(v);
                        if (!s.meetsLength) return 'At least 12 characters';
                        if (!s.meetsClasses) return 'Mix at least 3 of lowercase, uppercase, digit, symbol';
                        return null;
                      },
                    ),
                    if (_password.text.isNotEmpty) const SizedBox(height: 10),
                    if (_password.text.isNotEmpty)
                      PasswordStrengthMeter(password: _password.text),
                    if (_error != null) const SizedBox(height: 12),
                    if (_error != null)
                      Text(_error!,
                          style: TextStyle(color: theme.colorScheme.error)),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: canSubmit ? _submit : null,
                      child: _submitting
                          ? const SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Create account'),
                    ),
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: _submitting ? null : () => context.go('/login'),
                      child: const Text('Already have an account? Sign in'),
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
