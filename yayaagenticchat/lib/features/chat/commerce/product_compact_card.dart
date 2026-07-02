import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/product.dart';
import 'commerce_ui.dart';

/// A compact, tappable product card for the horizontal catalog carousel
/// (ecommerce-chat-design.md §10.1): hero image + title + price + one CTA. The
/// whole card opens the expanded sheet; the CTA adds directly (or opens the
/// sheet to pick a variant).
class ProductCompactCard extends StatelessWidget {
  const ProductCompactCard({
    super.key,
    required this.product,
    required this.accent,
    required this.onOpen,
    required this.onAdd,
    this.width = 168,
  });

  final Product product;
  final Color accent;
  final VoidCallback onOpen;
  final VoidCallback onAdd;
  final double width;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final badge = availabilityBadge(context, product.availability);
    final canAdd = product.availability.purchasable;
    // Products with variants must be opened to choose an option before adding.
    final primaryAdds = canAdd && !product.hasVariants;

    return SizedBox(
      width: width,
      child: Card(
        clipBehavior: Clip.antiAlias,
        margin: EdgeInsets.zero,
        child: InkWell(
          onTap: onOpen,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Stack(
                children: [
                  AspectRatio(
                    aspectRatio: 1,
                    child: ProductImage(url: product.heroImage),
                  ),
                  if (badge != null)
                    Positioned(
                      top: 6,
                      left: 6,
                      child: _Pill(text: badge.label, color: badge.color),
                    ),
                ],
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(10, 8, 10, 4),
                child: Text(
                  product.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodyMedium
                      ?.copyWith(fontWeight: FontWeight.w600),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                child: Text(
                  product.price?.format() ?? '',
                  style: Theme.of(context).textTheme.titleSmall
                      ?.copyWith(color: accent, fontWeight: FontWeight.w700),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(6, 4, 6, 6),
                child: SizedBox(
                  width: double.infinity,
                  child: TextButton(
                    onPressed: canAdd ? (primaryAdds ? onAdd : onOpen) : null,
                    style: TextButton.styleFrom(
                      foregroundColor: accent,
                      visualDensity: VisualDensity.compact,
                    ),
                    child: Text(primaryAdds ? t.addToCart : t.viewProduct),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.text, required this.color});
  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Text(text,
            style: const TextStyle(
                color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
      );
}
