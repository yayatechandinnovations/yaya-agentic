import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../chat_controller.dart';
import 'receipt_bubble.dart';

/// The live "ticket in progress" preview (chat-experience-design.md §7.8),
/// pinned just above the composer like the mini-cart bar. It updates in place
/// as the user builds their ticket play-by-play; tapping opens the full paper.
/// Collapses to nothing when there's no draft, and disappears the moment the
/// final receipt is issued.
class ReceiptDraftBar extends ConsumerWidget {
  const ReceiptDraftBar({super.key, required this.handle, required this.accent});

  final String handle;
  final Color accent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    final draft = ref.watch(chatControllerProvider(handle).select((s) => s.draftReceipt));
    if (draft == null) return const SizedBox.shrink();
    final template =
        ref.read(chatControllerProvider(handle).notifier).templateFor(draft);
    if (template == null) return const SizedBox.shrink();

    return Material(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: InkWell(
        onTap: () => showReceiptSheet(context,
            instance: draft, template: template, accent: accent, handle: handle),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 12, 8),
          child: Row(
            children: [
              Icon(Icons.receipt_long, color: accent, size: 20),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(t.receiptDraftTitle,
                        style: Theme.of(context)
                            .textTheme
                            .labelLarge
                            ?.copyWith(fontWeight: FontWeight.w600)),
                    Text(t.receiptDraftHint,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  ],
                ),
              ),
              Text(t.view,
                  style: TextStyle(color: accent, fontWeight: FontWeight.w600)),
              const Icon(Icons.expand_less, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}
