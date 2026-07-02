import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/product.dart';

/// A resilient product image: a network image with a loading shimmer and a
/// graceful placeholder when the merchant supplies no image or the URL 404s.
class ProductImage extends StatelessWidget {
  const ProductImage({super.key, this.url, this.width, this.height, this.fit = BoxFit.cover});

  final String? url;
  final double? width;
  final double? height;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      width: width,
      height: height,
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Icon(Icons.image_outlined,
          color: Theme.of(context).colorScheme.outline, size: 28),
    );
    if (url == null || url!.isEmpty) return placeholder;
    return Image.network(
      url!,
      width: width,
      height: height,
      fit: fit,
      errorBuilder: (_, _, _) => placeholder,
      loadingBuilder: (context, child, progress) {
        if (progress == null) return child;
        return Container(
          width: width,
          height: height,
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          child: const Center(
            child: SizedBox(
              width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
          ),
        );
      },
    );
  }
}

/// Human-readable stock label + colour; null when the item is in stock (no
/// badge needed). Greys out OUT items per §16.6.
({String label, Color color})? availabilityBadge(BuildContext context, Availability a) {
  final t = AppL10n.of(context);
  final scheme = Theme.of(context).colorScheme;
  switch (a) {
    case Availability.out:
      return (label: t.outOfStock, color: scheme.error);
    case Availability.low:
      return (label: t.lowStock, color: Colors.orange.shade800);
    case Availability.preorder:
      return (label: t.preorder, color: scheme.primary);
    case Availability.inStock:
      return null;
  }
}
