import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/money.dart';
import '../../../models/commerce/product.dart';
import 'commerce_ui.dart';

/// Open the expanded product card as an in-place bottom sheet over the live chat
/// (ecommerce-chat-design.md §10.1) — full gallery, description, variant picker,
/// quantity stepper, availability, Add-to-cart. Never a navigation away.
/// [onAdd] receives the chosen `variantId` (nullable) and quantity.
Future<void> showProductSheet(
  BuildContext context, {
  required Product product,
  required Color accent,
  required Future<void> Function(String? variantId, int qty) onAdd,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => _ExpandedProductSheet(product: product, accent: accent, onAdd: onAdd),
  );
}

class _ExpandedProductSheet extends StatefulWidget {
  const _ExpandedProductSheet({required this.product, required this.accent, required this.onAdd});

  final Product product;
  final Color accent;
  final Future<void> Function(String? variantId, int qty) onAdd;

  @override
  State<_ExpandedProductSheet> createState() => _ExpandedProductSheetState();
}

class _ExpandedProductSheetState extends State<_ExpandedProductSheet> {
  final _gallery = PageController();
  Variant? _selected;
  int _qty = 1;
  bool _busy = false;

  Product get p => widget.product;

  @override
  void initState() {
    super.initState();
    // Preselect the first purchasable variant so the CTA is actionable.
    if (p.hasVariants) {
      _selected = p.variants.firstWhere(
        (v) => v.availability.purchasable,
        orElse: () => p.variants.first,
      );
    }
  }

  @override
  void dispose() {
    _gallery.dispose();
    super.dispose();
  }

  Availability get _availability => _selected?.availability ?? p.availability;
  Money? get _price => _selected?.price ?? p.price;

  Future<void> _add() async {
    if (_busy) return;
    setState(() => _busy = true);
    await widget.onAdd(_selected?.variantId, _qty);
    if (mounted) Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final badge = availabilityBadge(context, _availability);
    final images = p.imageUrls.isEmpty ? <String?>[null] : p.imageUrls;

    return SafeArea(
      top: false,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.85),
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: AspectRatio(
                aspectRatio: 1.2,
                child: PageView(
                  controller: _gallery,
                  children: [for (final u in images) ProductImage(url: u)],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Text(p.title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Row(
              children: [
                Text(_price?.format() ?? '',
                    style: Theme.of(context).textTheme.titleMedium
                        ?.copyWith(color: widget.accent, fontWeight: FontWeight.w700)),
                const Spacer(),
                if (badge != null)
                  Text(badge.label,
                      style: TextStyle(color: badge.color, fontWeight: FontWeight.w600)),
              ],
            ),
            if (p.shortDescription.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(p.shortDescription, style: Theme.of(context).textTheme.bodyMedium),
            ],
            if (p.hasVariants) ...[
              const SizedBox(height: 16),
              Text(t.chooseOption, style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final v in p.variants)
                    ChoiceChip(
                      label: Text(v.label),
                      selected: _selected?.variantId == v.variantId,
                      onSelected: v.availability.purchasable
                          ? (_) => setState(() => _selected = v)
                          : null,
                    ),
                ],
              ),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Text(t.quantity, style: Theme.of(context).textTheme.labelLarge),
                const Spacer(),
                _QtyStepper(
                  qty: _qty,
                  min: 1,
                  onChanged: (q) => setState(() => _qty = q),
                ),
              ],
            ),
            const SizedBox(height: 20),
            FilledButton.icon(
              style: FilledButton.styleFrom(
                  backgroundColor: widget.accent, minimumSize: const Size.fromHeight(48)),
              onPressed: _availability.purchasable && !_busy ? _add : null,
              icon: _busy
                  ? const SizedBox(
                      width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.add_shopping_cart),
              label: Text(t.addToCart),
            ),
          ],
        ),
      ),
    );
  }
}

/// A −/＋ quantity control. When [min] is 0, hitting 0 is the "remove" signal.
class _QtyStepper extends StatelessWidget {
  const _QtyStepper({required this.qty, required this.onChanged, this.min = 0});
  final int qty;
  final int min;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton.outlined(
          visualDensity: VisualDensity.compact,
          onPressed: qty > min ? () => onChanged(qty - 1) : null,
          icon: const Icon(Icons.remove, size: 18),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Text('$qty', style: Theme.of(context).textTheme.titleMedium),
        ),
        IconButton.outlined(
          visualDensity: VisualDensity.compact,
          onPressed: () => onChanged(qty + 1),
          icon: const Icon(Icons.add, size: 18),
        ),
      ],
    );
  }
}

/// Shared stepper for the cart sheet (exported so we keep one implementation).
class QtyStepper extends StatelessWidget {
  const QtyStepper({super.key, required this.qty, required this.onChanged, this.min = 0});
  final int qty;
  final int min;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) =>
      _QtyStepper(qty: qty, onChanged: onChanged, min: min);
}
