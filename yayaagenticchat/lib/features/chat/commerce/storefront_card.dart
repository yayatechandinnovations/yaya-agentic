import 'package:flutter/material.dart';

/// The catalog-first storefront entry: a single tappable card that signals
/// "this chat is a shop" and opens the product grid on tap — the clickable
/// catalog card the shopper expects, distinct from the inline result carousel.
/// See commerce-redesign-authoring-rendering-paypal.md Pillar B §B.2.
class StorefrontCard extends StatelessWidget {
  const StorefrontCard({
    super.key,
    required this.accent,
    required this.onBrowse,
    this.busy = false,
  });

  final Color accent;
  final VoidCallback onBrowse;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      child: Material(
        color: accent.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(14),
        child: InkWell(
          borderRadius: BorderRadius.circular(14),
          onTap: busy ? null : onBrowse,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            child: Row(children: [
              CircleAvatar(
                radius: 18,
                backgroundColor: accent.withValues(alpha: 0.18),
                child: Icon(Icons.storefront, color: accent, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('Browse our products',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                    Text('Tap to see the catalog',
                        style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
                  ],
                ),
              ),
              busy
                  ? const SizedBox.square(
                      dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : FilledButton.tonal(
                      onPressed: onBrowse,
                      style: FilledButton.styleFrom(
                          backgroundColor: accent, foregroundColor: Colors.white),
                      child: const Text('Browse'),
                    ),
            ]),
          ),
        ),
      ),
    );
  }
}
