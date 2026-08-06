import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/receipts.dart';
import '../chat_controller.dart';
import 'receipt_paper.dart';

/// Inline receipt bubble (chat-experience-design.md §7.6): a compressed paper
/// preview in the message list; tap opens the full-screen sheet with pinch
/// zoom and the hint's action buttons (each a normal /actions tap — authz'd
/// server-side like every other tap).
class ReceiptBubble extends ConsumerWidget {
  const ReceiptBubble({
    super.key,
    required this.instance,
    required this.template,
    required this.accent,
    this.handle,
  });

  final ReceiptInstance instance;
  final ReceiptTemplate? template;
  final Color accent;
  final String? handle;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = template ?? instance.snapshot;
    if (t == null) return const SizedBox.shrink();
    return Align(
      alignment: Alignment.centerLeft,
      child: GestureDetector(
        onTap: () => showReceiptSheet(context,
            instance: instance, template: t, accent: accent, handle: handle),
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 6),
          constraints: const BoxConstraints(maxHeight: 260, maxWidth: 230),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(8),
            boxShadow: [
              BoxShadow(
                  color: Colors.black.withValues(alpha: 0.25),
                  blurRadius: 8,
                  offset: const Offset(0, 3)),
            ],
          ),
          // Compressed preview: top of the paper, faded out at the fold.
          child: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: ShaderMask(
              shaderCallback: (rect) => const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Colors.white, Colors.white, Colors.transparent],
                stops: [0, 0.82, 1],
              ).createShader(rect),
              blendMode: BlendMode.dstIn,
              child: SingleChildScrollView(
                physics: const NeverScrollableScrollPhysics(),
                child: ReceiptPaper(template: t, data: instance.data, width: 230),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

Future<void> showReceiptSheet(
  BuildContext context, {
  required ReceiptInstance instance,
  required ReceiptTemplate template,
  required Color accent,
  String? handle,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (ctx) => Consumer(
      builder: (ctx, ref, _) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.85,
        maxChildSize: 0.95,
        builder: (ctx, controller) => Column(
          children: [
            Expanded(
              child: InteractiveViewer(
                maxScale: 3,
                child: SingleChildScrollView(
                  controller: controller,
                  padding: const EdgeInsets.all(16),
                  child: Center(
                    child: ReceiptPaper(
                        template: template,
                        data: instance.data,
                        width: template.paperProfile == '80mm' ? 340 : 300),
                  ),
                ),
              ),
            ),
            if (instance.actions.isNotEmpty && handle != null)
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Row(
                    children: [
                      for (final action in instance.actions) ...[
                        Expanded(
                          child: FilledButton(
                            style: FilledButton.styleFrom(backgroundColor: accent),
                            onPressed: () {
                              Navigator.of(ctx).pop();
                              ref
                                  .read(chatControllerProvider(handle).notifier)
                                  .invokeReceiptAction(action);
                            },
                            child: Text(action.label.isEmpty
                                ? AppL10n.of(ctx).ok
                                : action.label),
                          ),
                        ),
                        const SizedBox(width: 8),
                      ],
                    ]..removeLast(),
                  ),
                ),
              ),
          ],
        ),
      ),
    ),
  );
}
