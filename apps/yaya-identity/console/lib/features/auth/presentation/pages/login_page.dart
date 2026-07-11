import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/app_theme.dart';
import '../bloc/auth_bloc.dart';

class LoginPage extends StatelessWidget {
  const LoginPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppBrand.navy,
      body: Center(
        child: SingleChildScrollView(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 380),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: BlocBuilder<AuthBloc, AuthState>(
                  builder: (context, state) {
                    return Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        // The lockup ships on brand navy — give it its own tile.
                        Container(
                          decoration: BoxDecoration(
                            color: AppBrand.navy,
                            borderRadius: BorderRadius.circular(14),
                          ),
                          padding: const EdgeInsets.all(10),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: Image.asset(AppBrand.logoAsset, width: 180),
                          ),
                        ),
                        const SizedBox(height: 16),
                        const Text('Admin console', style: TextStyle(color: Colors.black54)),
                        const SizedBox(height: 24),
                        // Failure or an expired session — both carry a message.
                        if (state.error != null) ...[
                          Semantics(
                            liveRegion: true,
                            child: Text(state.error!, style: const TextStyle(color: Colors.red)),
                          ),
                          const SizedBox(height: 16),
                        ],
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            onPressed: state.status == AuthStatus.authenticating
                                ? null
                                : () =>
                                    context.read<AuthBloc>().add(const AuthLoginRequested()),
                            icon: const Icon(Icons.login),
                            label: const Text('Sign in with yaya-identity'),
                          ),
                        ),
                        if (state.status == AuthStatus.authenticating) ...[
                          const SizedBox(height: 16),
                          const CircularProgressIndicator(),
                        ],
                        const SizedBox(height: 12),
                        TextButton(
                          onPressed: () => context.go('/signup'),
                          child: const Text('New here? Create your organization'),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
