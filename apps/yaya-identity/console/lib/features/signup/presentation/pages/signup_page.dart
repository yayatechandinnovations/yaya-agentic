import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../bloc/signup_bloc.dart';

/// Public self-registration: org details → phone OTP → done. The server may
/// refuse with 403 when the install keeps signup disabled — the message is
/// shown verbatim.
class SignupPage extends StatefulWidget {
  const SignupPage({super.key});

  @override
  State<SignupPage> createState() => _SignupPageState();
}

class _SignupPageState extends State<SignupPage> {
  final _formKey = GlobalKey<FormState>();
  final _orgName = TextEditingController();
  final _displayName = TextEditingController();
  final _email = TextEditingController();
  final _phone = TextEditingController();
  final _password = TextEditingController();
  final _code = TextEditingController();

  @override
  void dispose() {
    for (final c in [_orgName, _displayName, _email, _phone, _password, _code]) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: BlocBuilder<SignupBloc, SignupState>(
                  builder: (context, state) => switch (state.status) {
                    SignupStatus.form || SignupStatus.submitting => _form(state),
                    SignupStatus.codeSent || SignupStatus.verifying => _codeEntry(state),
                    SignupStatus.completed => _done(state),
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _form(SignupState state) {
    final busy = state.status == SignupStatus.submitting;
    return Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('Create your organization',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          const Text('We verify your phone number with a one-time code.',
              style: TextStyle(color: Colors.black54)),
          const SizedBox(height: 24),
          if (state.error != null) ...[
            Semantics(
              liveRegion: true,
              child: Text(state.error!, style: const TextStyle(color: Colors.red)),
            ),
            const SizedBox(height: 16),
          ],
          TextFormField(
            controller: _orgName,
            decoration: const InputDecoration(labelText: 'Organization name'),
            validator: _required,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _displayName,
            decoration: const InputDecoration(labelText: 'Your full name'),
            validator: _required,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _email,
            decoration: const InputDecoration(labelText: 'Email'),
            keyboardType: TextInputType.emailAddress,
            validator: (v) =>
                v == null || !v.contains('@') ? 'a valid email is required' : null,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _phone,
            decoration: const InputDecoration(
              labelText: 'Phone (E.164)',
              hintText: '+18095551234',
            ),
            keyboardType: TextInputType.phone,
            validator: _required,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: _password,
            decoration: const InputDecoration(
              labelText: 'Password',
              helperText: 'At least 12 characters',
            ),
            obscureText: true,
            validator: (v) =>
                v == null || v.length < 12 ? 'password must be at least 12 characters' : null,
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: busy
                ? null
                : () {
                    if (_formKey.currentState?.validate() ?? false) {
                      context.read<SignupBloc>().add(SignupSubmitted(
                            orgName: _orgName.text.trim(),
                            displayName: _displayName.text.trim(),
                            email: _email.text.trim(),
                            phone: _phone.text.trim(),
                            password: _password.text,
                          ));
                    }
                  },
            child: busy
                ? const SizedBox(
                    height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Send verification code'),
          ),
          TextButton(
            onPressed: () => context.go('/signin'),
            child: const Text('Already have an account? Sign in'),
          ),
        ],
      ),
    );
  }

  Widget _codeEntry(SignupState state) {
    final busy = state.status == SignupStatus.verifying;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('Enter the verification code',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        Text('We sent a code by SMS to ${state.phone ?? 'your phone'}.',
            style: const TextStyle(color: Colors.black54)),
        if (state.devOtp != null) ...[
          const SizedBox(height: 8),
          Text('Dev code: ${state.devOtp}',
              style: const TextStyle(color: Colors.orange, fontFamily: 'monospace')),
        ],
        const SizedBox(height: 24),
        if (state.error != null) ...[
          Semantics(
            liveRegion: true,
            child: Text(state.error!, style: const TextStyle(color: Colors.red)),
          ),
          const SizedBox(height: 16),
        ],
        if (state.message != null) ...[
          Text(state.message!, style: const TextStyle(color: Colors.green)),
          const SizedBox(height: 16),
        ],
        TextField(
          controller: _code,
          decoration: const InputDecoration(labelText: '6-digit code'),
          keyboardType: TextInputType.number,
          maxLength: 6,
          onSubmitted: busy ? null : (_) => _submitCode(),
        ),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: busy ? null : _submitCode,
          child: busy
              ? const SizedBox(
                  height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Verify'),
        ),
        TextButton(
          onPressed:
              busy ? null : () => context.read<SignupBloc>().add(const SignupResendRequested()),
          child: const Text('Resend code'),
        ),
        TextButton(
          onPressed: busy ? null : () => context.read<SignupBloc>().add(const SignupRestarted()),
          child: const Text('Start over'),
        ),
      ],
    );
  }

  void _submitCode() {
    if (_code.text.trim().isNotEmpty) {
      context.read<SignupBloc>().add(SignupCodeSubmitted(_code.text.trim()));
    }
  }

  Widget _done(SignupState state) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Icon(Icons.check_circle, color: Colors.green, size: 48),
        const SizedBox(height: 16),
        Text('${state.verified?.orgName ?? 'Your organization'} is ready',
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        const Text('You are the owner. Sign in to start managing it.',
            textAlign: TextAlign.center, style: TextStyle(color: Colors.black54)),
        const SizedBox(height: 24),
        FilledButton(
          onPressed: () => context.go('/signin'),
          child: const Text('Sign in'),
        ),
      ],
    );
  }

  static String? _required(String? v) => v == null || v.trim().isEmpty ? 'required' : null;
}
