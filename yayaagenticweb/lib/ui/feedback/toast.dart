import 'package:flutter/material.dart';

/// Outcome messages ("Tool created", "Deleted · Undo"). Never for validation —
/// those belong next to the field (see YForm).
void showToast(
  BuildContext context,
  String message, {
  bool error = false,
  String? actionLabel,
  VoidCallback? onAction,
}) {
  final messenger = ScaffoldMessenger.maybeOf(context);
  if (messenger == null) return;
  final scheme = Theme.of(context).colorScheme;
  final fg = error ? scheme.onErrorContainer : scheme.onInverseSurface;
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(SnackBar(
    backgroundColor: error ? scheme.errorContainer : scheme.inverseSurface,
    // Errors wait for the operator; a vanishing error is one they never read.
    duration: error ? const Duration(seconds: 10) : const Duration(seconds: 4),
    showCloseIcon: error,
    closeIconColor: fg,
    content: Row(children: [
      Icon(error ? Icons.error_outline : Icons.check_circle_outline, color: fg, size: 20),
      const SizedBox(width: 12),
      Expanded(child: Text(message, style: TextStyle(color: fg, fontSize: 14))),
    ]),
    action: actionLabel == null
        ? null
        : SnackBarAction(
            label: actionLabel,
            textColor: error ? scheme.error : scheme.inversePrimary,
            onPressed: onAction ?? () {},
          ),
  ));
}
