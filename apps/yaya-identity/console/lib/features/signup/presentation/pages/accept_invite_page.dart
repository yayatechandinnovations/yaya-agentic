import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../bloc/accept_invite_bloc.dart';

/// Invitation redemption: the link carries `?token=…`; the invitee chooses a
/// display name and password and lands on sign-in.
class AcceptInvitePage extends StatefulWidget {
  const AcceptInvitePage({super.key, this.token});

  final String? token;

  @override
  State<AcceptInvitePage> createState() => _AcceptInvitePageState();
}

class _AcceptInvitePageState extends State<AcceptInvitePage> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _token = TextEditingController(text: widget.token ?? '');
  final _displayName = TextEditingController();
  final _password = TextEditingController();

  @override
  void dispose() {
    _token.dispose();
    _displayName.dispose();
    _password.dispose();
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
                child: BlocBuilder<AcceptInviteBloc, AcceptInviteState>(
                  builder: (context, state) => state.status == AcceptInviteStatus.completed
                      ? _done()
                      : _form(state),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _form(AcceptInviteState state) {
    final busy = state.status == AcceptInviteStatus.submitting;
    return Form(
      key: _formKey,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('Join your organization',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          const Text('Use the invitation your administrator sent you.',
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
            controller: _token,
            decoration: const InputDecoration(labelText: 'Invitation token'),
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
            controller: _password,
            decoration: const InputDecoration(
              labelText: 'Choose a password',
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
                      context.read<AcceptInviteBloc>().add(AcceptInviteSubmitted(
                            token: _token.text.trim(),
                            displayName: _displayName.text.trim(),
                            password: _password.text,
                          ));
                    }
                  },
            child: busy
                ? const SizedBox(
                    height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Accept invitation'),
          ),
        ],
      ),
    );
  }

  Widget _done() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Icon(Icons.check_circle, color: Colors.green, size: 48),
        const SizedBox(height: 16),
        const Text('You are in',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        const Text('Your account is active. Sign in with your email and new password.',
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
