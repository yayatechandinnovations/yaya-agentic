import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../bloc/auth_bloc.dart';

class LoginPage extends StatelessWidget {
  const LoginPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 360),
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: BlocBuilder<AuthBloc, AuthState>(
                builder: (context, state) {
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
                          child: Text(state.error!, style: const TextStyle(color: Colors.red)),
                        ),
                        const SizedBox(height: 16),
                      ],
                      FilledButton.icon(
                        onPressed: state.status == AuthStatus.authenticating
                            ? null
                            : () => context.read<AuthBloc>().add(const AuthLoginRequested()),
                        icon: const Icon(Icons.login),
                        label: const Text('Sign in with yaya-identity'),
                      ),
                      if (state.status == AuthStatus.authenticating) ...[
                        const SizedBox(height: 16),
                        const CircularProgressIndicator(),
                      ],
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
