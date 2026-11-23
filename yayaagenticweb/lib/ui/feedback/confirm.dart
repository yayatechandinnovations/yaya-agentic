import 'package:flutter/material.dart';

/// A confirm dialog that names the thing and the consequence.
Future<bool> confirmAction(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
  String cancelLabel = 'Cancel',
  bool destructive = false,
}) async {
  final scheme = Theme.of(context).colorScheme;
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Text(message, style: const TextStyle(fontSize: 14, height: 1.5)),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(cancelLabel)),
        FilledButton(
          style: destructive ? FilledButton.styleFrom(backgroundColor: scheme.error, foregroundColor: scheme.onError) : null,
          onPressed: () => Navigator.of(ctx).pop(true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return ok == true;
}

/// For high-blast-radius actions: the operator types [expected] to proceed.
Future<bool> confirmTyped(
  BuildContext context, {
  required String title,
  required String message,
  required String expected,
  required String confirmLabel,
}) async {
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => _TypedConfirm(title: title, message: message, expected: expected, confirmLabel: confirmLabel),
  );
  return ok == true;
}

class _TypedConfirm extends StatefulWidget {
  const _TypedConfirm({required this.title, required this.message, required this.expected, required this.confirmLabel});
  final String title, message, expected, confirmLabel;

  @override
  State<_TypedConfirm> createState() => _TypedConfirmState();
}

class _TypedConfirmState extends State<_TypedConfirm> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final matches = _ctrl.text.trim() == widget.expected;
    return AlertDialog(
      title: Text(widget.title),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(widget.message, style: const TextStyle(fontSize: 14, height: 1.5)),
          const SizedBox(height: 16),
          Text.rich(TextSpan(children: [
            const TextSpan(text: 'Type '),
            TextSpan(text: widget.expected, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
            const TextSpan(text: ' to confirm'),
          ]), style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
          const SizedBox(height: 6),
          TextField(
            controller: _ctrl,
            autofocus: true,
            style: const TextStyle(fontFamily: 'monospace'),
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) {
              if (matches) Navigator.of(context).pop(true);
            },
          ),
        ]),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: scheme.error, foregroundColor: scheme.onError),
          onPressed: matches ? () => Navigator.of(context).pop(true) : null,
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}
