import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/checkout_result.dart';
import 'checkout_handler.dart';

/// The in-stream record of a checkout hand-off. The framework auto-triggers the
/// hand-off once (via the pendingCheckout listener); this card lets the shopper
/// re-open the payment surface if they dismissed it, and confirms a placed order.
class CheckoutResultCard extends ConsumerWidget {
  const CheckoutResultCard({
    super.key,
    required this.result,
    required this.handle,
    required this.accent,
  });

  final CheckoutResult result;
  final String handle;
  final Color accent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = AppL10n.of(context);
    final placed = result.mode == CheckoutMode.orderPlaced;
    final payable =
        result.mode == CheckoutMode.hostedUrl || result.mode == CheckoutMode.paymentIntent;

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(placed ? Icons.check_circle : Icons.shopping_bag_outlined,
                    color: placed ? Colors.green.shade600 : accent),
                const SizedBox(width: 8),
                Text(
                  placed ? t.orderPlacedTitle : t.checkout,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const Spacer(),
                Text(result.amount.format(),
                    style: Theme.of(context).textTheme.titleMedium
                        ?.copyWith(fontWeight: FontWeight.w700)),
              ],
            ),
            if (result.orderRef != null) ...[
              const SizedBox(height: 4),
              Text(t.orderRefLabel(result.orderRef!),
                  style: Theme.of(context).textTheme.bodySmall
                      ?.copyWith(color: Theme.of(context).colorScheme.outline)),
            ],
            if (payable) ...[
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: accent),
                  onPressed: () => handleCheckout(context, ref, handle, result),
                  icon: const Icon(Icons.lock_outline, size: 18),
                  label: Text(t.payNow),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
