import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../../../models/commerce/catalog_payload.dart';
import '../../../models/commerce/facet.dart';
import '../../../models/commerce/product.dart';
import 'facet_bar.dart';
import 'product_compact_card.dart';

/// The full storefront browse view (Pillar B §B.2): a dedicated grid over the
/// live chat — search field, instant local facet filters, a scrollable product
/// grid, and server-paged "Load more". Distinct from the inline result carousel:
/// this is where a shopper browses the *whole* catalog after tapping the
/// storefront card. Product taps and add-to-cart route through the same handlers
/// the chat uses (two doors, one lock).
class StorefrontBrowseSheet extends StatefulWidget {
  const StorefrontBrowseSheet({
    super.key,
    required this.accent,
    required this.onSearch,
    required this.onOpenProduct,
    required this.onAddProduct,
  });

  final Color accent;

  /// Fetch a page of the catalog (query + zero-based page) without touching chat.
  final Future<CatalogPayload?> Function(String query, int page) onSearch;
  final void Function(Product product) onOpenProduct;
  final void Function(Product product) onAddProduct;

  @override
  State<StorefrontBrowseSheet> createState() => _StorefrontBrowseSheetState();
}

class _StorefrontBrowseSheetState extends State<StorefrontBrowseSheet> {
  final _searchCtrl = TextEditingController();
  final Set<FacetSelection> _selected = {};

  final List<Product> _items = [];
  List<Facet> _facets = const [];
  int _page = 0;
  int _total = 0;
  bool _hasMore = false;
  String _query = '';

  bool _loading = true;
  bool _loadingMore = false;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load({required bool reset}) async {
    setState(() {
      if (reset) { _loading = true; } else { _loadingMore = true; }
    });
    final page = reset ? 0 : _page + 1;
    final catalog = await widget.onSearch(_query, page);
    if (!mounted) return;
    setState(() {
      _loading = false;
      _loadingMore = false;
      if (catalog == null) return;
      if (reset) {
        _items
          ..clear()
          ..addAll(catalog.items);
        _selected.clear();
      } else {
        _items.addAll(catalog.items);
      }
      _facets = catalog.facets;
      _page = catalog.pageIndex;
      _total = catalog.total;
      _hasMore = catalog.hasMore;
    });
  }

  void _submitSearch(String text) {
    _query = text.trim();
    _load(reset: true);
  }

  void _toggleFacet(FacetSelection s) => setState(() {
        if (!_selected.remove(s)) _selected.add(s);
      });

  @override
  Widget build(BuildContext context) {
    final t = AppL10n.of(context);
    final scheme = Theme.of(context).colorScheme;
    // Facets filter the already-loaded set locally — zero latency, no round-trip.
    final visible = applyFacetFilter(_items, _facets, _selected);

    return DraggableScrollableSheet(
      initialChildSize: 0.9,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      expand: false,
      builder: (context, scrollController) => Column(
        children: [
          const SizedBox(height: 8),
          Container(width: 40, height: 4, decoration: BoxDecoration(
              color: scheme.outlineVariant, borderRadius: BorderRadius.circular(2))),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 8, 4),
            child: Row(children: [
              Icon(Icons.storefront, color: widget.accent),
              const SizedBox(width: 8),
              Expanded(child: Text('Products',
                  style: Theme.of(context).textTheme.titleMedium)),
              IconButton(onPressed: () => Navigator.of(context).maybePop(),
                  icon: const Icon(Icons.close)),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: TextField(
              controller: _searchCtrl,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                isDense: true,
                prefixIcon: const Icon(Icons.search, size: 20),
                hintText: 'Search products',
                border: const OutlineInputBorder(),
                suffixIcon: _searchCtrl.text.isEmpty ? null : IconButton(
                  icon: const Icon(Icons.clear, size: 18),
                  onPressed: () { _searchCtrl.clear(); _submitSearch(''); },
                ),
              ),
              onSubmitted: _submitSearch,
            ),
          ),
          if (_facets.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: FacetBar(
                facets: _facets, selected: _selected,
                onToggle: _toggleFacet, accent: widget.accent),
            ),
          if (_total > 0 && !_loading)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
              child: Align(alignment: Alignment.centerLeft,
                child: Text(
                  _query.isEmpty ? '$_total product(s)' : t.resultsForQuery(_total, _query),
                  style: Theme.of(context).textTheme.labelMedium
                      ?.copyWith(color: scheme.outline))),
            ),
          Expanded(child: _grid(context, scrollController, visible, t)),
        ],
      ),
    );
  }

  Widget _grid(BuildContext context, ScrollController scrollController,
      List<Product> visible, AppL10n t) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (visible.isEmpty) {
      return Center(child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(_items.isEmpty ? 'No products yet.' : 'No products match those filters.')));
    }
    // Facets are a local filter, so "Load more" only makes sense on the full set.
    final showLoadMore = _hasMore && _selected.isEmpty;
    return SingleChildScrollView(
      controller: scrollController,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: 12,
            runSpacing: 12,
            alignment: WrapAlignment.center,
            children: [
              for (final p in visible)
                ProductCompactCard(
                  product: p,
                  accent: widget.accent,
                  width: 168,
                  onOpen: () => widget.onOpenProduct(p),
                  onAdd: () => widget.onAddProduct(p),
                ),
            ],
          ),
          if (showLoadMore) ...[
            const SizedBox(height: 16),
            Center(
              child: _loadingMore
                  ? const SizedBox.square(dimension: 24, child: CircularProgressIndicator(strokeWidth: 2))
                  : OutlinedButton.icon(
                      onPressed: () => _load(reset: false),
                      icon: const Icon(Icons.more_horiz),
                      label: Text(t.loadMore),
                    ),
            ),
          ],
        ],
      ),
    );
  }
}
