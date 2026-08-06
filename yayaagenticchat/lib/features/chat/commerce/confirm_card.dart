import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/confirm_prompt.dart';

/// The two-phase confirm guardrail before a money-mover runs (§5.3, §9),
/// pinned above the composer. User-facing copy only: the localized question +
/// the operator-authored prompt when the tenant wrote one — never tool ids or
/// arguments — with full-width, unmissable Confirm/Cancel buttons.
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

  /// The engine's neutral fallback when no operator copy exists — the app
  /// replaces it with its own localized question instead of echoing English
  /// at a Spanish-speaking user.
  static const _genericFallback = 'Do you want to continue?';

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;
    final detail = prompt.summary.trim();
    final showDetail = detail.isNotEmpty && detail != _genericFallback;

    return Material(
      color: scheme.secondaryContainer,
      child: SafeArea(
        top: false,
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Icon(Icons.help_outline, size: 22, color: accent),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      showDetail ? detail : t.confirmTitle,
                      style: Theme.of(context)
                          .textTheme
                          .titleMedium
                          ?.copyWith(fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
              if (showDetail) ...[
                const SizedBox(height: 2),
                Padding(
                  padding: const EdgeInsets.only(left: 32),
                  child: Text(t.confirmTitle,
                      style: Theme.of(context)
                          .textTheme
                          .bodySmall
                          ?.copyWith(color: scheme.onSurfaceVariant)),
                ),
              ],
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: busy ? null : onCancel,
                      style: OutlinedButton.styleFrom(
                          minimumSize: const Size.fromHeight(44)),
                      child: Text(t.cancel),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      style: FilledButton.styleFrom(
                          backgroundColor: accent,
                          minimumSize: const Size.fromHeight(44)),
                      onPressed: busy ? null : onConfirm,
                      child: busy
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : Text(t.confirmAction),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
