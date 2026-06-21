import 'package:flutter/material.dart';

/// Reusable destructive-action confirmation. Asks the user to type the
/// resource's own name back exactly before enabling the destructive CTA.
/// Matches the UI-UX §5.6 pattern.
///
/// Returns `true` only after a successful confirmation; closes with
/// `null` on cancel.
class ConfirmTypedNameDialog extends StatefulWidget {
  const ConfirmTypedNameDialog({
    super.key,
    required this.title,
    required this.body,
    required this.expectedName,
    required this.confirmLabel,
    this.confirmColor,
  });

  final String title;
  final Widget body;
  /// The string the user must type back exactly. Case-sensitive.
  final String expectedName;
  final String confirmLabel;
  final Color? confirmColor;

  @override
  State<ConfirmTypedNameDialog> createState() => _ConfirmTypedNameDialogState();
}

class _ConfirmTypedNameDialogState extends State<ConfirmTypedNameDialog> {
  final _ctrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _ctrl.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final matches = _ctrl.text == widget.expectedName;
    final color = widget.confirmColor ?? theme.colorScheme.error;

    return AlertDialog(
      title: Text(widget.title),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            widget.body,
            const SizedBox(height: 16),
            Text(
              "Type ${widget.expectedName} to confirm:",
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 6),
            TextField(
              controller: _ctrl,
              autofocus: true,
              decoration: InputDecoration(
                border: const OutlineInputBorder(),
                hintText: widget.expectedName,
                isDense: true,
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: matches ? () => Navigator.of(context).pop(true) : null,
          style: FilledButton.styleFrom(backgroundColor: color),
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}
