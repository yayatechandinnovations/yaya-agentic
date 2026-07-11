import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../bloc/auth_bloc.dart';

/// yaya-identity is the ONLY sign-in method, so an unauthenticated arrival is
/// taken straight into the OAuth flow — no button to click. The button only
/// appears when auto-redirecting would be wrong: after an explicit logout
/// (the IdP's SSO cookie would silently sign the operator back in) or after a
/// failed exchange (auto-retry would loop).
class LoginPage extends StatefulWidget {
  const LoginPage({super.key});

  @override
  State<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  bool _kicked = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _maybeAutoSignIn(context.read<AuthBloc>().state);
    });
  }

  void _maybeAutoSignIn(AuthState state) {
    if (_kicked) return;
    if (state.status == AuthStatus.unauthenticated && !state.signedOut) {
      _kicked = true;
      context.read<AuthBloc>().add(const AuthLoginRequested());
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 360),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: BlocConsumer<AuthBloc, AuthState>(
                listener: (context, state) => _maybeAutoSignIn(state),
                builder: (context, state) {
                  final redirecting = state.status == AuthStatus.authenticating ||
                      (state.status == AuthStatus.unknown) ||
                      (_kicked && state.status == AuthStatus.unauthenticated);
                  return Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text('yaya-sales',
                          style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      const Text('Merchant admin console',
                          style: TextStyle(color: Colors.black54)),
                      const SizedBox(height: 24),
                      if (state.status == AuthStatus.failure && state.error != null) ...[
                        Semantics(
                          liveRegion: true,
                          child: Text(state.error!,
                              style: const TextStyle(color: Colors.red)),
                        ),
                        const SizedBox(height: 16),
                      ] else if (state.signedOut) ...[
                        const Text("You're signed out.",
                            style: TextStyle(color: Colors.black54)),
                        const SizedBox(height: 16),
                      ],
                      if (redirecting) ...[
                        const CircularProgressIndicator(),
                        const SizedBox(height: 12),
                        const Text('Taking you to sign-in…',
                            style: TextStyle(color: Colors.black54, fontSize: 13)),
                      ] else
                        FilledButton.icon(
                          onPressed: () => context
                              .read<AuthBloc>()
                              .add(const AuthLoginRequested()),
                          icon: const Icon(Icons.login),
                          label: const Text('Sign in with yaya-identity'),
                        ),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
  }
}
