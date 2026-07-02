import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../models/turn_event.dart';
import 'playground_controller.dart';

/// Renders the commerce UiHint kinds the engine streams (`product_catalog`,
/// `product_card`, `checkout_result`) as cards in the chat, plus the persistent
/// cart bar + editable sheet. Every mutating control routes through the
/// client-initiated action channel (`dispatchAction`) — the same executor +
/// Authorizer path a chat tool call takes. See ecommerce-chat-design §10, §15.

String formatMoney(num? amountMinor, String? currency) {
  final minor = (amountMinor ?? 0).toInt();
  final major = (minor / 100).toStringAsFixed(2);
  final sym = switch (currency) {
    'USD' => '\$',
    'DOP' => 'RD\$',
    'EUR' => '€',
    _ => '${currency ?? ''} ',
  };
  return '$sym$major';
}

int _minor(Map? money) => (money?['amountMinor'] as num?)?.toInt() ?? 0;

String _productId(Map product) {
  final id = product['id'];
  if (id is Map) return id['value']?.toString() ?? '';
  return id?.toString() ?? '';
}

/// Dispatches a commerce hint attached to a message to its renderer.
class CommerceHintCard extends StatelessWidget {
  const CommerceHintCard({super.key, required this.hint});
  final UiHintEvent hint;

  @override
  Widget build(BuildContext context) {
    return switch (hint.kind) {
      'product_catalog' => ProductCatalogCard(payload: hint.payload),
      'product_card' => ProductCardWidget(product: hint.payload),
      'checkout_result' => CheckoutResultCard(payload: hint.payload),
      _ => const SizedBox.shrink(),
    };
  }
}

// ---------------------------------------------------------------------------
// product_catalog — compact carousel + instant client-side facet filtering
// ---------------------------------------------------------------------------

class ProductCatalogCard extends ConsumerStatefulWidget {
  const ProductCatalogCard({super.key, required this.payload});
  final Map<String, dynamic> payload;

  @override
  ConsumerState<ProductCatalogCard> createState() => _ProductCatalogCardState();
}

class _ProductCatalogCardState extends ConsumerState<ProductCatalogCard> {
  // facetKey -> selected value (categorical) ; 'price' -> "min:max" bucket key.
  final Map<String, String> _selected = {};

  List<Map> get _items =>
      (widget.payload['items'] as List? ?? const []).whereType<Map>().toList();

  List<Map> get _facets =>
      (widget.payload['facets'] as List? ?? const []).whereType<Map>().toList();

  bool _matches(Map item) {
    for (final entry in _selected.entries) {
      if (entry.key == 'price') {
        final parts = entry.value.split(':');
        final min = parts[0].isEmpty ? null : int.tryParse(parts[0]);
        final max = parts[1].isEmpty ? null : int.tryParse(parts[1]);
        final price = _minor(item['price'] as Map?);
        if (min != null && price < min) return false;
        if (max != null && price >= max) return false;
      } else if (entry.key == 'category') {
        if (item['category']?.toString() != entry.value) return false;
      } else {
        final attrs = item['attributes'];
        if (attrs is! Map || attrs[entry.key]?.toString() != entry.value) return false;
      }
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _items.where(_matches).toList();
    final echo = (widget.payload['query'] as Map?)?['echo']?.toString() ?? '';
    final total = (widget.payload['total'] as num?)?.toInt() ?? _items.length;

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              echo.isEmpty ? '$total products' : '$total results for "$echo"',
              style: Theme.of(context).textTheme.labelMedium,
            ),
            const SizedBox(height: 6),
            ..._facets.map((f) => _FacetRow(
                  facet: f,
                  selected: _selected[f['key']?.toString() ?? ''],
                  onSelect: (value) => setState(() {
                    final key = f['key']?.toString() ?? '';
                    if (value == null) {
                      _selected.remove(key);
                    } else {
                      _selected[key] = value;
                    }
                  }),
                )),
            const SizedBox(height: 8),
            SizedBox(
              height: 214,
              child: filtered.isEmpty
                  ? const Center(child: Text('No products match those filters.'))
                  : ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: filtered.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 8),
                      itemBuilder: (_, i) => _CompactProduct(product: filtered[i]),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FacetRow extends StatelessWidget {
  const _FacetRow({required this.facet, required this.selected, required this.onSelect});
  final Map facet;
  final String? selected;
  final ValueChanged<String?> onSelect;

  @override
  Widget build(BuildContext context) {
    final label = facet['label']?.toString() ?? facet['key']?.toString() ?? '';
    final values = (facet['values'] as List? ?? const []).whereType<Map>().toList();
    final buckets = (facet['buckets'] as List? ?? const []).whereType<Map>().toList();
    final isPrice = buckets.isNotEmpty;

    final chips = <Widget>[];
    if (isPrice) {
      for (final b in buckets) {
        final key = '${b['min'] ?? ''}:${b['max'] ?? ''}';
        chips.add(_chip(context, b['label']?.toString() ?? '', '${b['count'] ?? ''}',
            selected == key, () => onSelect(selected == key ? null : key)));
      }
    } else {
      for (final v in values) {
        final value = v['v']?.toString() ?? '';
        chips.add(_chip(context, value, '${v['count'] ?? ''}',
            selected == value, () => onSelect(selected == value ? null : value)));
      }
    }
    if (chips.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 76,
            child: Text(label, style: Theme.of(context).textTheme.labelSmall),
          ),
          Expanded(child: Wrap(spacing: 6, runSpacing: 4, children: chips)),
        ],
      ),
    );
  }

  Widget _chip(BuildContext context, String label, String count, bool on, VoidCallback onTap) {
    return FilterChip(
      label: Text(count.isEmpty ? label : '$label ($count)'),
      selected: on,
      onSelected: (_) => onTap(),
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
    );
  }
}

class _CompactProduct extends ConsumerWidget {
  const _CompactProduct({required this.product});
  final Map product;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final images = (product['imageUrls'] as List?) ?? const [];
    final notifier = ref.read(playgroundProvider.notifier);
    final id = _productId(product);
    final out = product['availability']?.toString() == 'OUT';
    return SizedBox(
      width: 150,
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ProductImage(url: images.isEmpty ? null : images.first.toString(), height: 88),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 8, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product['title']?.toString() ?? '',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                  const SizedBox(height: 2),
                  Text(formatMoney(_minor(product['price'] as Map?),
                      (product['price'] as Map?)?['currency']?.toString())),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton(
                          onPressed: out ? null : () => notifier.dispatchAction(
                              'add_to_cart', {'productId': id, 'qty': 1}),
                          style: FilledButton.styleFrom(
                              padding: const EdgeInsets.symmetric(horizontal: 4),
                              minimumSize: const Size(0, 32)),
                          child: Text(out ? 'Out' : 'Add', style: const TextStyle(fontSize: 12)),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Details',
                        visualDensity: VisualDensity.compact,
                        onPressed: () => notifier.dispatchAction('get_product', {'productId': id}),
                        icon: const Icon(Icons.info_outline, size: 18),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// product_card — expanded single product
// ---------------------------------------------------------------------------

class ProductCardWidget extends ConsumerWidget {
  const ProductCardWidget({super.key, required this.product});
  final Map<String, dynamic> product;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final images = (product['imageUrls'] as List?) ?? const [];
    final id = _productId(product);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ProductImage(url: images.isEmpty ? null : images.first.toString(), height: 96, width: 96),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product['title']?.toString() ?? '',
                      style: Theme.of(context).textTheme.titleSmall),
                  const SizedBox(height: 2),
                  Text(product['shortDescription']?.toString() ?? '',
                      style: Theme.of(context).textTheme.bodySmall),
                  const SizedBox(height: 6),
                  Text(formatMoney(_minor(product['price'] as Map?),
                      (product['price'] as Map?)?['currency']?.toString())),
                  const SizedBox(height: 8),
                  FilledButton.icon(
                    onPressed: () => ref.read(playgroundProvider.notifier)
                        .dispatchAction('add_to_cart', {'productId': id, 'qty': 1}),
                    icon: const Icon(Icons.add_shopping_cart, size: 18),
                    label: const Text('Add to cart'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// checkout_result — hand-off to the PSP
// ---------------------------------------------------------------------------

class CheckoutResultCard extends StatelessWidget {
  const CheckoutResultCard({super.key, required this.payload});
  final Map<String, dynamic> payload;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final mode = payload['mode']?.toString();
    final amount = formatMoney(payload['amountMinor'] as num?, payload['currency']?.toString());
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      color: scheme.tertiaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              const Icon(Icons.receipt_long, size: 18),
              const SizedBox(width: 8),
              Text('Checkout · $amount',
                  style: Theme.of(context).textTheme.titleSmall),
            ]),
            const SizedBox(height: 4),
            Text('Order ${payload['orderRef'] ?? ''}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            const SizedBox(height: 6),
            if (mode == 'hosted_url')
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Continue to the secure payment page:'),
                const SizedBox(height: 2),
                SelectableText(payload['url']?.toString() ?? '',
                    style: TextStyle(color: scheme.primary, fontSize: 12)),
              ])
            else if (mode == 'payment_intent')
              const Text('Mount the PSP drop-in with the returned client secret.')
            else
              Text('Order status: ${payload['status'] ?? 'placed'}'),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// cart — persistent bar + editable bottom sheet
// ---------------------------------------------------------------------------

class CartBar extends ConsumerWidget {
  const CartBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(playgroundProvider.select((s) => s.cart));
    final lines = (cart?['lines'] as List?) ?? const [];
    if (cart == null || lines.isEmpty) return const SizedBox.shrink();

    final count = lines.fold<int>(0, (n, l) => n + ((l as Map)['qty'] as num? ?? 0).toInt());
    final subtotal = formatMoney(_minor(cart['subtotal'] as Map?), cart['currency']?.toString());
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.secondaryContainer,
      child: InkWell(
        onTap: () => _openCartSheet(context, ref),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(children: [
            Icon(Icons.shopping_cart_outlined, size: 18, color: scheme.onSecondaryContainer),
            const SizedBox(width: 8),
            Text('$count ${count == 1 ? 'item' : 'items'} · $subtotal',
                style: TextStyle(color: scheme.onSecondaryContainer, fontWeight: FontWeight.w600)),
            const Spacer(),
            FilledButton.icon(
              onPressed: () => ref.read(playgroundProvider.notifier).dispatchAction('checkout'),
              icon: const Icon(Icons.lock_outline, size: 16),
              label: const Text('Checkout'),
            ),
          ]),
        ),
      ),
    );
  }
}

void _openCartSheet(BuildContext context, WidgetRef ref) {
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (_) => Consumer(builder: (context, ref, _) {
      final cart = ref.watch(playgroundProvider.select((s) => s.cart));
      final lines = ((cart?['lines'] as List?) ?? const []).whereType<Map>().toList();
      final notifier = ref.read(playgroundProvider.notifier);
      if (lines.isEmpty) {
        return const Padding(padding: EdgeInsets.all(24), child: Text('Your cart is empty.'));
      }
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Your cart', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ...lines.map((l) => _CartLine(line: l, notifier: notifier)),
            const Divider(),
            Row(children: [
              Text('Subtotal', style: Theme.of(context).textTheme.titleSmall),
              const Spacer(),
              Text(formatMoney(_minor(cart!['subtotal'] as Map?), cart['currency']?.toString()),
                  style: Theme.of(context).textTheme.titleSmall),
            ]),
            const SizedBox(height: 12),
            Row(children: [
              TextButton.icon(
                onPressed: () { notifier.dispatchAction('clear_cart'); Navigator.of(context).pop(); },
                icon: const Icon(Icons.delete_outline),
                label: const Text('Clear'),
              ),
              const Spacer(),
              FilledButton.icon(
                onPressed: () { notifier.dispatchAction('checkout'); Navigator.of(context).pop(); },
                icon: const Icon(Icons.lock_outline),
                label: const Text('Checkout'),
              ),
            ]),
          ]),
        ),
      );
    }),
  );
}

class _CartLine extends StatelessWidget {
  const _CartLine({required this.line, required this.notifier});
  final Map line;
  final PlaygroundController notifier;

  @override
  Widget build(BuildContext context) {
    final lineId = line['lineId']?.toString() ?? '';
    final qty = (line['qty'] as num? ?? 0).toInt();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(children: [
        _ProductImage(url: line['image']?.toString(), height: 40, width: 40),
        const SizedBox(width: 8),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(line['title']?.toString() ?? '', maxLines: 1, overflow: TextOverflow.ellipsis),
            Text(formatMoney(_minor(line['lineTotal'] as Map?),
                (line['lineTotal'] as Map?)?['currency']?.toString()),
                style: Theme.of(context).textTheme.bodySmall),
          ]),
        ),
        IconButton(
          visualDensity: VisualDensity.compact,
          onPressed: () => notifier.dispatchAction('set_cart_quantity', {'lineId': lineId, 'qty': qty - 1}),
          icon: const Icon(Icons.remove_circle_outline, size: 20),
        ),
        Text('$qty'),
        IconButton(
          visualDensity: VisualDensity.compact,
          onPressed: () => notifier.dispatchAction('set_cart_quantity', {'lineId': lineId, 'qty': qty + 1}),
          icon: const Icon(Icons.add_circle_outline, size: 20),
        ),
        IconButton(
          tooltip: 'Remove',
          visualDensity: VisualDensity.compact,
          onPressed: () => notifier.dispatchAction('remove_from_cart', {'lineId': lineId}),
          icon: const Icon(Icons.close, size: 18),
        ),
      ]),
    );
  }
}

class _ProductImage extends StatelessWidget {
  const _ProductImage({required this.url, required this.height, this.width});
  final String? url;
  final double height;
  final double? width;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      height: height,
      width: width ?? double.infinity,
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Icon(Icons.image_outlined, color: Theme.of(context).colorScheme.outline),
    );
    if (url == null || url!.isEmpty) return placeholder;
    return Image.network(
      url!,
      height: height,
      width: width ?? double.infinity,
      fit: BoxFit.cover,
      errorBuilder: (_, _, _) => placeholder,
    );
  }
}
