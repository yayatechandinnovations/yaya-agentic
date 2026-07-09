import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../bloc/auth_bloc.dart';

/// Lands the OAuth redirect: dispatches the code for exchange and shows progress.
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
    return const Scaffold(
      body: Center(child: CircularProgressIndicator()),
    );
  }
}
