import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../chat_controller.dart';
import 'cart_sheet.dart';

/// The sticky mini-cart bar pinned just above the composer (§10.3): a
/// thumb-reachable running total that doubles as the primary checkout CTA. It
/// collapses to nothing when the cart is empty, so it never steals space
/// pre-shopping. Tapping the total opens the cart; the button starts checkout.
class MiniCartBar extends ConsumerWidget {
  const MiniCartBar({super.key, required this.handle, required this.accent});
  final String handle;
  final Color accent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    final state = ref.watch(chatControllerProvider(handle));
    final cart = state.cart;
    if (cart == null || cart.isEmpty) return const SizedBox.shrink();
    final controller = ref.read(chatControllerProvider(handle).notifier);

    return Material(
      color: accent,
      child: InkWell(
        onTap: () => showCartSheet(context, handle, accent),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
          child: Row(
            children: [
              const Icon(Icons.shopping_cart, color: Colors.white, size: 20),
              const SizedBox(width: 10),
              Text(
                '${t.itemsCount(cart.itemCount)} · ${cart.subtotal?.format() ?? ''}',
                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
              ),
              const Spacer(),
              TextButton(
                onPressed: state.sending ? null : controller.checkout,
                style: TextButton.styleFrom(
                  foregroundColor: accent,
                  backgroundColor: Colors.white,
                ),
                child: Text(t.checkout),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
