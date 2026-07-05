import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/product.dart';
import '../shared/admin_shared.dart';
import 'csv_codec.dart';
import 'csv_web.dart';
import 'product_editor_dialog.dart';

/// The managed-product workspace for one catalog — a paged, spreadsheet-like
/// table with a quick-add row, CSV import/export, and a full per-product editor.
/// This is the answer to "imagine creating 100 products": type-Enter-repeat for
/// quick entry, CSV round-trip for bulk, full editor for depth. See
/// commerce-redesign-authoring-rendering-paypal.md Pillar A §7.1, §A.5.
class ManagedProductsScreen extends ConsumerStatefulWidget {
  const ManagedProductsScreen({super.key, required this.catalogId});
  final String catalogId;

  @override
  ConsumerState<ManagedProductsScreen> createState() => _ManagedProductsState();
}

class _ManagedProductsState extends ConsumerState<ManagedProductsScreen> {
  static const _pageSize = 25;

  String _currency = 'USD';
  String _displayName = '';
  ProductsPage? _page;
  bool _loading = true;
  String? _error;

  // filters
  final _searchCtrl = TextEditingController();
  String _status = 'ACTIVE';
  String _sort = 'SORT_ORDER';
  int _pageIndex = 0;

  // quick-add row
  final _qSku = TextEditingController();
  final _qName = TextEditingController();
  final _qPrice = TextEditingController();
  final _qInv = TextEditingController();
  bool _adding = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    _qSku.dispose();
    _qName.dispose();
    _qPrice.dispose();
    _qInv.dispose();
    super.dispose();
  }

  String? get _tenant => ref.read(currentTenantOrNull);

  Future<void> _bootstrap() async {
    final tenant = _tenant;
    if (tenant == null) {
      setState(() { _loading = false; _error = 'select a tenant'; });
      return;
    }
    try {
      final api = await ref.read(adminApiProvider.future);
      final cat = await api.getProductCatalog(tenant: tenant, id: widget.catalogId);
      _currency = cat.currency;
      _displayName = cat.displayName;
      await _reload();
    } catch (e) {
      if (mounted) setState(() { _loading = false; _error = formatError(e); });
    }
  }

  Future<void> _reload() async {
    final tenant = _tenant;
    if (tenant == null) return;
    setState(() { _loading = true; _error = null; });
    try {
      final api = await ref.read(adminApiProvider.future);
      final page = await api.listProducts(
        tenant: tenant,
        catalogId: widget.catalogId,
        page: _pageIndex,
        size: _pageSize,
        q: _searchCtrl.text.trim(),
        status: _status,
        sort: _sort,
      );
      if (!mounted) return;
      setState(() { _page = page; _loading = false; });
    } catch (e) {
      if (mounted) setState(() { _loading = false; _error = formatError(e); });
    }
  }

  void _goPage(int i) {
    setState(() => _pageIndex = i);
    _reload();
  }

  Future<void> _quickAdd() async {
    final tenant = _tenant;
    if (tenant == null) return;
    final sku = _qSku.text.trim();
    final name = _qName.text.trim();
    if (sku.isEmpty || name.isEmpty) {
      showSnack(context, 'SKU and name are required', error: true);
      return;
    }
    final priceMinor = ((double.tryParse(_qPrice.text.trim()) ?? 0) * 100).round();
    final inv = int.tryParse(_qInv.text.trim());
    setState(() => _adding = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createProduct(tenant: tenant, catalogId: widget.catalogId, body: {
        'productId': sku,
        'title': name,
        'priceMinor': priceMinor,
        'currency': _currency,
        if (inv != null) 'inventory': inv,
      });
      _qSku.clear(); _qName.clear(); _qPrice.clear(); _qInv.clear();
      await _reload();
      if (mounted) FocusScope.of(context).requestFocus(_skuFocus);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }

  final _skuFocus = FocusNode();

  Future<void> _openEditor({ProductRow? row}) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => ProductEditorDialog(
        catalogId: widget.catalogId,
        currency: _currency,
        productId: row?.productId,   // null → create
      ),
    );
    if (saved == true) await _reload();
  }

  Future<void> _toggleArchive(ProductRow row) async {
    final tenant = _tenant;
    if (tenant == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.archiveProduct(
        tenant: tenant, catalogId: widget.catalogId,
        productId: row.productId, restore: row.archived,
      );
      await _reload();
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _exportCsv() async {
    final tenant = _tenant;
    if (tenant == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final csv = await api.exportProductsCsv(tenant: tenant, catalogId: widget.catalogId);
      downloadCsv('${widget.catalogId}-products.csv', csv);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _importCsv() async {
    final tenant = _tenant;
    if (tenant == null) return;
    final text = await pickCsvText();
    if (text == null) return;
    final parsed = parseProductsCsv(text);
    if (!mounted) return;
    final go = await showDialog<bool>(
      context: context,
      builder: (_) => _ImportPreviewDialog(result: parsed),
    );
    if (go != true || parsed.bodies.isEmpty) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final res = await api.bulkUpsertProducts(
        tenant: tenant, catalogId: widget.catalogId, products: parsed.bodies);
      final upserted = (res['upserted'] as num?)?.toInt() ?? 0;
      final errs = (res['errors'] as List?) ?? const [];
      if (mounted) {
        showSnack(context, 'Imported $upserted product(s)'
            '${errs.isEmpty ? '' : ', ${errs.length} row error(s)'}',
            error: errs.isNotEmpty);
      }
      await _reload();
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Products');
    }
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        leading: BackButton(onPressed: () => Navigator.of(context).maybePop()),
        title: Text(_displayName.isEmpty ? widget.catalogId : '$_displayName · products'),
        actions: [
          TextButton.icon(onPressed: _importCsv,
              icon: const Icon(Icons.upload_file), label: const Text('Import CSV')),
          TextButton.icon(onPressed: _exportCsv,
              icon: const Icon(Icons.download), label: const Text('Export CSV')),
          const SizedBox(width: 8),
          FilledButton.icon(onPressed: () => _openEditor(),
              icon: const Icon(Icons.add), label: const Text('New product')),
          const SizedBox(width: 12),
        ],
      ),
      body: Column(
        children: [
          _toolbar(context),
          _quickAddRow(context),
          const Divider(height: 1),
          Expanded(child: _body(context, scheme)),
          const Divider(height: 1),
          _footer(context),
        ],
      ),
    );
  }

  Widget _toolbar(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
      child: Row(children: [
        SizedBox(
          width: 320,
          child: TextField(
            controller: _searchCtrl,
            decoration: InputDecoration(
              isDense: true,
              prefixIcon: const Icon(Icons.search, size: 18),
              hintText: 'Search name or SKU',
              border: const OutlineInputBorder(),
              suffixIcon: _searchCtrl.text.isEmpty ? null : IconButton(
                icon: const Icon(Icons.clear, size: 18),
                onPressed: () { _searchCtrl.clear(); _goPage(0); },
              ),
            ),
            onSubmitted: (_) => _goPage(0),
          ),
        ),
        const SizedBox(width: 12),
        _dropdown('Status', _status, const {
          'ACTIVE': 'Active', 'ARCHIVED': 'Archived', 'ALL': 'All'
        }, (v) { setState(() => _status = v); _goPage(0); }),
        const SizedBox(width: 12),
        _dropdown('Sort', _sort, const {
          'SORT_ORDER': 'Manual order', 'TITLE': 'Name', 'PRICE': 'Price', 'UPDATED': 'Recently updated'
        }, (v) { setState(() => _sort = v); _goPage(0); }),
      ]),
    );
  }

  Widget _dropdown(String label, String value, Map<String, String> options, ValueChanged<String> onChanged) {
    return DropdownButton<String>(
      value: value,
      underline: const SizedBox.shrink(),
      items: options.entries
          .map((e) => DropdownMenuItem(value: e.key, child: Text('$label: ${e.value}')))
          .toList(),
      onChanged: (v) { if (v != null) onChanged(v); },
    );
  }

  Widget _quickAddRow(BuildContext context) {
    return Container(
      color: Theme.of(context).colorScheme.surfaceContainerLow,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Row(children: [
        const SizedBox(width: 44, child: Icon(Icons.add_box_outlined, size: 20)),
        Expanded(flex: 2, child: TextField(
          controller: _qSku, focusNode: _skuFocus,
          decoration: const InputDecoration(isDense: true, hintText: 'SKU', border: OutlineInputBorder()),
          onSubmitted: (_) => _quickAdd(),
        )),
        const SizedBox(width: 8),
        Expanded(flex: 4, child: TextField(
          controller: _qName,
          decoration: const InputDecoration(isDense: true, hintText: 'Name', border: OutlineInputBorder()),
          onSubmitted: (_) => _quickAdd(),
        )),
        const SizedBox(width: 8),
        Expanded(flex: 2, child: TextField(
          controller: _qPrice,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(isDense: true, hintText: 'Price',
              prefixText: _currency == 'DOP' ? r'RD$ ' : r'$ ', border: const OutlineInputBorder()),
          onSubmitted: (_) => _quickAdd(),
        )),
        const SizedBox(width: 8),
        Expanded(flex: 2, child: TextField(
          controller: _qInv,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(isDense: true, hintText: 'Stock', border: OutlineInputBorder()),
          onSubmitted: (_) => _quickAdd(),
        )),
        const SizedBox(width: 8),
        FilledButton.tonal(
          onPressed: _adding ? null : _quickAdd,
          child: _adding
              ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Add'),
        ),
      ]),
    );
  }

  Widget _body(BuildContext context, ColorScheme scheme) {
    if (_loading && _page == null) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(child: Text(_error!, style: TextStyle(color: scheme.error)));
    }
    final page = _page;
    if (page == null || page.items.isEmpty) {
      return const Center(child: Padding(
        padding: EdgeInsets.all(24),
        child: Text('No products yet. Use the quick-add row above, or Import CSV.'),
      ));
    }
    return Stack(children: [
      ListView.separated(
        itemCount: page.items.length,
        separatorBuilder: (_, _) => const Divider(height: 1),
        itemBuilder: (_, i) => _ProductRowTile(
          row: page.items[i],
          currency: _currency,
          onEdit: () => _openEditor(row: page.items[i]),
          onToggleArchive: () => _toggleArchive(page.items[i]),
        ),
      ),
      if (_loading) const LinearProgressIndicator(minHeight: 2),
    ]);
  }

  Widget _footer(BuildContext context) {
    final page = _page;
    final total = page?.total ?? 0;
    final pageCount = page?.pageCount ?? 1;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(children: [
        Text('$total product(s)', style: Theme.of(context).textTheme.bodySmall),
        const Spacer(),
        IconButton(
          onPressed: _pageIndex == 0 ? null : () => _goPage(_pageIndex - 1),
          icon: const Icon(Icons.chevron_left),
        ),
        Text('Page ${_pageIndex + 1} of $pageCount'),
        IconButton(
          onPressed: (_pageIndex + 1) >= pageCount ? null : () => _goPage(_pageIndex + 1),
          icon: const Icon(Icons.chevron_right),
        ),
      ]),
    );
  }
}

// ---------------------------------------------------------------------------

class _ProductRowTile extends StatelessWidget {
  const _ProductRowTile({
    required this.row, required this.currency,
    required this.onEdit, required this.onToggleArchive,
  });
  final ProductRow row;
  final String currency;
  final VoidCallback onEdit;
  final VoidCallback onToggleArchive;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dimmed = row.archived;
    return Opacity(
      opacity: dimmed ? 0.55 : 1,
      child: ListTile(
        onTap: onEdit,
        leading: SizedBox(
          width: 44, height: 44,
          child: row.thumbnail == null
              ? Container(
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest, borderRadius: BorderRadius.circular(6)),
                  child: Icon(Icons.image_outlined, size: 18, color: scheme.outline))
              : ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: Image.network(row.thumbnail!, width: 44, height: 44, fit: BoxFit.cover,
                      errorBuilder: (_, _, _) => Icon(Icons.broken_image_outlined, color: scheme.outline))),
        ),
        title: Text(row.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(row.productId, style: TextStyle(color: scheme.outline, fontSize: 12)),
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
          _availabilityChip(context, row.availability),
          const SizedBox(width: 12),
          SizedBox(
            width: 92,
            child: Text(formatMoney(row.priceMinor, currency),
                textAlign: TextAlign.right,
                style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
          IconButton(
            tooltip: row.archived ? 'Restore' : 'Archive',
            icon: Icon(row.archived ? Icons.unarchive_outlined : Icons.archive_outlined, size: 20),
            onPressed: onToggleArchive,
          ),
          IconButton(icon: const Icon(Icons.edit_outlined, size: 20), onPressed: onEdit),
        ]),
      ),
    );
  }

  Widget _availabilityChip(BuildContext context, String availability) {
    final scheme = Theme.of(context).colorScheme;
    final (label, color) = switch (availability) {
      'OUT' => ('Out', scheme.error),
      'LOW' => ('Low', Colors.orange),
      'PREORDER' => ('Preorder', scheme.tertiary),
      _ => ('In stock', scheme.primary),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(10)),
      child: Text(label, style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w600)),
    );
  }
}

class _ImportPreviewDialog extends StatelessWidget {
  const _ImportPreviewDialog({required this.result});
  final CsvImportResult result;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Import products'),
      content: SizedBox(
        width: 460,
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${result.bodies.length} row(s) ready to import.'),
            if (result.errors.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text('${result.errors.length} row(s) skipped:',
                  style: TextStyle(color: scheme.error, fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 160),
                child: SingleChildScrollView(
                  child: Text(result.errors.join('\n'),
                      style: TextStyle(color: scheme.error, fontSize: 12)),
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        FilledButton(
          onPressed: result.bodies.isEmpty ? null : () => Navigator.pop(context, true),
          child: Text('Import ${result.bodies.length}'),
        ),
      ],
    );
  }
}

/// Format integer minor units as a currency string (2-decimal markets only, which
/// is what the admin offers today).
String formatMoney(int minor, String currency) {
  final symbol = currency == 'DOP' ? r'RD$' : (currency == 'USD' ? r'$' : '$currency ');
  return '$symbol${(minor / 100).toStringAsFixed(2)}';
}
