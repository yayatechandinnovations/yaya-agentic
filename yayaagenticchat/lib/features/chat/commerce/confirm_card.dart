import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/confirm_prompt.dart';

/// The two-phase confirm guardrail before a money-mover runs (§5.3, §9),
/// pinned above the composer. User-facing copy only: the localized question +
/// the operator-authored prompt when the tenant wrote one — never tool ids or
/// arguments — with full-width, unmissable Confirm/Cancel buttons.
///
/// Multi-item mode (owner-ai-experience v2 §4): when the prompt carries
/// `items`, the card renders a checkbox list (initial state from each item's
/// `checked`), the `totalLine`, and approve/decline buttons labeled
/// `confirmLabel` (fallback "SÍ") / "NO". Approval fires [onConfirmItems] with
/// the ids of the CHECKED items; the single-confirm path is untouched.
class ConfirmCard extends StatefulWidget {
  const ConfirmCard({
    super.key,
    required this.prompt,
    required this.accent,
    required this.onConfirm,
    required this.onCancel,
    this.onConfirmItems,
    this.busy = false,
  });

  final ConfirmPrompt prompt;
  final Color accent;
  final VoidCallback onConfirm;
  final VoidCallback onCancel;

  /// Multi-item approval — receives the checked item ids. When null the card
  /// falls back to [onConfirm] even if items are present.
  final void Function(List<String> itemIds)? onConfirmItems;

  final bool busy;

  /// The engine's neutral fallback when no operator copy exists — the app
  /// replaces it with its own localized question instead of echoing English
  /// at a Spanish-speaking user.
  static const _genericFallback = 'Do you want to continue?';

  @override
  State<ConfirmCard> createState() => _ConfirmCardState();
}

class _ConfirmCardState extends State<ConfirmCard> {
  /// Checked ids for the multi-item mode, seeded from the payload.
  late Set<String> _checked;

  @override
  void initState() {
    super.initState();
    _seed();
  }

  @override
  void didUpdateWidget(ConfirmCard old) {
    super.didUpdateWidget(old);
    // A NEW confirm frame resets the selection to the server's initial state.
    if (!identical(old.prompt, widget.prompt)) _seed();
  }

  void _seed() {
    _checked = {
      for (final i in widget.prompt.items)
        if (i.checked) i.id,
    };
  }

  bool get _multi => widget.prompt.hasItems && widget.onConfirmItems != null;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;
    final prompt = widget.prompt;
    final detail = prompt.summary.trim();
    final showDetail = detail.isNotEmpty && detail != ConfirmCard._genericFallback;

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
                  Icon(Icons.help_outline, size: 22, color: widget.accent),
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
              if (showDetail && !_multi) ...[
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
              if (_multi) ...[
                const SizedBox(height: 6),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 220),
                  child: SingleChildScrollView(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        for (final item in prompt.items)
                          CheckboxListTile(
                            dense: true,
                            visualDensity: VisualDensity.compact,
                            contentPadding: EdgeInsets.zero,
                            controlAffinity: ListTileControlAffinity.leading,
                            activeColor: widget.accent,
                            value: _checked.contains(item.id),
                            onChanged: widget.busy
                                ? null
                                : (v) => setState(() => v == true
                                    ? _checked.add(item.id)
                                    : _checked.remove(item.id)),
                            title: Text(item.label,
                                style: Theme.of(context).textTheme.bodyMedium),
                          ),
                      ],
                    ),
                  ),
                ),
                if ((prompt.totalLine ?? '').trim().isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(prompt.totalLine!,
                      style: Theme.of(context)
                          .textTheme
                          .bodyMedium
                          ?.copyWith(fontWeight: FontWeight.w600)),
                ],
              ],
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: widget.busy ? null : widget.onCancel,
                      style: OutlinedButton.styleFrom(
                          minimumSize: const Size.fromHeight(44)),
                      child: Text(_multi ? 'NO' : t.cancel),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      style: FilledButton.styleFrom(
                          backgroundColor: widget.accent,
                          minimumSize: const Size.fromHeight(44)),
                      onPressed: widget.busy || (_multi && _checked.isEmpty)
                          ? null
                          : _multi
                              ? () => widget.onConfirmItems!([
                                    for (final i in prompt.items)
                                      if (_checked.contains(i.id)) i.id,
                                  ])
                              : widget.onConfirm,
                      child: widget.busy
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : Text(_multi
                              ? ((prompt.confirmLabel ?? '').trim().isEmpty
                                  ? 'SÍ'
                                  : prompt.confirmLabel!)
                              : t.confirmAction),
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
