import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../chat_controller.dart';
import 'cart_sheet.dart';

/// The always-reachable cart anchor in the app bar (§10.3), shown for the whole
/// session once the profile says `cartEnabled` — before the first add, so it
/// also signals "this is a shop." Badge shows the live item count.
class CartHeaderButton extends ConsumerWidget {
  const CartHeaderButton({super.key, required this.handle, required this.accent});
  final String handle;
  final Color accent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(chatControllerProvider(handle).select((s) => s.cartCount));
    return IconButton(
      tooltip: 'Cart',
      onPressed: () => showCartSheet(context, handle, accent),
      icon: Badge(
        isLabelVisible: count > 0,
        label: Text('$count'),
        child: const Icon(Icons.shopping_cart_outlined, color: Colors.white),
      ),
    );
  }
}
