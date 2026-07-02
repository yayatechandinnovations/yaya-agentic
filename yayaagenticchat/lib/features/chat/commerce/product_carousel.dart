import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/catalog_payload.dart';
import '../../../models/commerce/facet.dart';
import '../../../models/commerce/product.dart';
import 'facet_bar.dart';
import 'product_compact_card.dart';

/// The `product_catalog` renderer: a results header, an instant local filter row
/// (facets travel in the payload — §10.2), and a horizontal swipeable carousel
/// of compact cards. Filtering never round-trips the model; "Load more" and
/// typed intent searches do (§10).
class ProductCarousel extends StatefulWidget {
  const ProductCarousel({
    super.key,
    required this.catalog,
    required this.accent,
    required this.onOpenProduct,
    required this.onAddProduct,
    required this.onLoadMore,
  });

  final CatalogPayload catalog;
  final Color accent;
  final void Function(Product) onOpenProduct;
  final void Function(Product) onAddProduct;
  final void Function(String query, int nextPage) onLoadMore;

  @override
  State<ProductCarousel> createState() => _ProductCarouselState();
}

class _ProductCarouselState extends State<ProductCarousel> {
  final Set<FacetSelection> _selected = {};

  void _toggle(FacetSelection s) => setState(() {
        if (!_selected.remove(s)) _selected.add(s);
      });

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final c = widget.catalog;
    final filtered = applyFacetFilter(c.items, c.facets, _selected);

    if (c.items.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (c.total > 0)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text(
              t.resultsForQuery(c.total, c.queryEcho.isEmpty ? '…' : c.queryEcho),
              style: Theme.of(context).textTheme.labelMedium
                  ?.copyWith(color: Theme.of(context).colorScheme.outline),
            ),
          ),
        FacetBar(
          facets: c.facets,
          selected: _selected,
          onToggle: _toggle,
          accent: widget.accent,
        ),
        const SizedBox(height: 6),
        SizedBox(
          height: 268,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: filtered.length + (c.hasMore && _selected.isEmpty ? 1 : 0),
            separatorBuilder: (_, _) => const SizedBox(width: 8),
            itemBuilder: (context, i) {
              if (i >= filtered.length) {
                return _LoadMoreCard(
                  onTap: () => widget.onLoadMore(c.queryEcho, c.pageIndex + 1),
                );
              }
              final p = filtered[i];
              return ProductCompactCard(
                product: p,
                accent: widget.accent,
                onOpen: () => widget.onOpenProduct(p),
                onAdd: () => widget.onAddProduct(p),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _LoadMoreCard extends StatelessWidget {
  const _LoadMoreCard({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    return SizedBox(
      width: 120,
      child: Card(
        margin: EdgeInsets.zero,
        child: InkWell(
          onTap: onTap,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.more_horiz),
                const SizedBox(height: 6),
                Text(t.loadMore, textAlign: TextAlign.center),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
