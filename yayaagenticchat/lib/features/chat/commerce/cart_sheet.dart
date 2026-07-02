import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/cart.dart';
import '../chat_controller.dart';
import 'commerce_ui.dart';
import 'product_card_expanded.dart';

/// Open the editable cart as a bottom sheet over the live chat (§10.3). All
/// three cart affordances (header button, mini-bar, inline hint) open this same
/// widget; every edit runs through the `/actions` channel (§9).
Future<void> showCartSheet(BuildContext context, String handle, Color accent) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => _CartSheet(handle: handle, accent: accent),
  );
}

class _CartSheet extends ConsumerWidget {
  const _CartSheet({required this.handle, required this.accent});
  final String handle;
  final Color accent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    final state = ref.watch(chatControllerProvider(handle));
    final controller = ref.read(chatControllerProvider(handle).notifier);
    final cart = state.cart;
    final empty = cart == null || cart.isEmpty;

    return SafeArea(
      top: false,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Row(
                children: [
                  Text(t.cartTitle, style: Theme.of(context).textTheme.titleLarge),
                  const Spacer(),
                  if (!empty)
                    Text(t.itemsCount(cart.itemCount),
                        style: Theme.of(context).textTheme.bodyMedium
                            ?.copyWith(color: Theme.of(context).colorScheme.outline)),
                ],
              ),
            ),
            if (empty)
              Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  children: [
                    Icon(Icons.shopping_cart_outlined,
                        size: 48, color: Theme.of(context).colorScheme.outline),
                    const SizedBox(height: 12),
                    Text(t.cartEmpty),
                  ],
                ),
              )
            else ...[
              Flexible(
                child: ListView.separated(
                  shrinkWrap: true,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  itemCount: cart.lines.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, i) => _CartLineTile(
                    line: cart.lines[i],
                    controller: controller,
                  ),
                ),
              ),
              const Divider(height: 1),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
                child: Row(
                  children: [
                    Text(t.subtotal, style: Theme.of(context).textTheme.titleMedium),
                    const Spacer(),
                    Text(cart.subtotal?.format() ?? '',
                        style: Theme.of(context).textTheme.titleLarge
                            ?.copyWith(fontWeight: FontWeight.w700)),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(
                        backgroundColor: accent, minimumSize: const Size.fromHeight(48)),
                    onPressed: () {
                      Navigator.of(context).maybePop();
                      controller.checkout();
                    },
                    icon: const Icon(Icons.lock_outline, size: 18),
                    label: Text(t.checkout),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _CartLineTile extends StatelessWidget {
  const _CartLineTile({required this.line, required this.controller});
  final CartLine line;
  final ChatController controller;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: ProductImage(url: line.image, width: 56, height: 56),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodyLarge),
                const SizedBox(height: 2),
                Text(line.lineTotal?.format() ?? line.unitPrice?.format() ?? '',
                    style: Theme.of(context).textTheme.bodyMedium
                        ?.copyWith(fontWeight: FontWeight.w600)),
                const SizedBox(height: 6),
                QtyStepper(
                  qty: line.qty,
                  min: 0,
                  onChanged: (q) => q <= 0
                      ? controller.removeLine(line.lineId)
                      : controller.setQty(line.lineId, q),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: AppL10n.of(context).removeItem,
            icon: const Icon(Icons.delete_outline),
            onPressed: () => controller.removeLine(line.lineId),
          ),
        ],
      ),
    );
  }
}
