import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../bloc/auth_bloc.dart';

/// Lands the OAuth redirect: dispatches the code for exchange and shows
/// progress. Navigation away happens via the router's redirect once the auth
/// state turns terminal; a missing code (user hit /callback directly) offers
/// the way back instead of spinning forever.
class CallbackPage extends StatefulWidget {
  const CallbackPage({super.key, required this.code});

  final String? code;

  @override
  State<CallbackPage> createState() => _CallbackPageState();
}

class _CallbackPageState extends State<CallbackPage> {
  @override
  void initState() {
    super.initState();
    if (widget.code != null) {
      context.read<AuthBloc>().add(AuthCodeReceived(widget.code!));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.code == null) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('This page completes a sign-in and was opened without one.'),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () => context.go('/signin'),
                child: const Text('Go to sign in'),
              ),
            ],
          ),
        ),
      );
    }
    return const Scaffold(
      body: Center(child: CircularProgressIndicator()),
    );
  }
}
