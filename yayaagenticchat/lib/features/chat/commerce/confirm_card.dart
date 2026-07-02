import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/confirm_prompt.dart';

/// The two-phase confirm guardrail before a money-mover runs (§5.3, §9). Shown
/// pinned above the composer; Confirm/Cancel send a canonical yes/no the engine
/// resolves against the pending tool.
class ConfirmCard extends StatelessWidget {
  const ConfirmCard({
    super.key,
    required this.prompt,
    required this.accent,
    required this.onConfirm,
    required this.onCancel,
    this.busy = false,
  });

  final ConfirmPrompt prompt;
  final Color accent;
  final VoidCallback onConfirm;
  final VoidCallback onCancel;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return Material(
      color: Theme.of(context).colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
        child: Row(
          children: [
            const Icon(Icons.help_outline, size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                prompt.summary,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
            TextButton(onPressed: busy ? null : onCancel, child: Text(t.cancel)),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: accent),
              onPressed: busy ? null : onConfirm,
              child: busy
                  ? const SizedBox(
                      width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(t.confirmAction),
            ),
          ],
        ),
      ),
    );
  }
}
