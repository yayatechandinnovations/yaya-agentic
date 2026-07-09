import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/domain/money.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/catalog_entities.dart';
import '../bloc/products_bloc.dart';

/// Products of one catalog: authoring table, archive/restore, CSV import/export.
class CatalogProductsPage extends StatelessWidget {
  const CatalogProductsPage({super.key, required this.catalogId});

  final String catalogId;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/catalog'),
        ),
        title: Text('Catalog $catalogId'),
        actions: [
          TextButton.icon(
            onPressed: () => _promptImport(context),
            icon: const Icon(Icons.upload_file),
            label: const Text('Import CSV'),
          ),
          TextButton.icon(
            onPressed: () =>
                context.read<ProductsBloc>().add(const ProductsExportRequested()),
            icon: const Icon(Icons.download),
            label: const Text('Export CSV'),
          ),
          const SizedBox(width: 8),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptEditor(context, null),
        icon: const Icon(Icons.add),
        label: const Text('New product'),
      ),
      body: BlocConsumer<ProductsBloc, ProductsState>(
        listener: (context, state) {
          if (state.status == ProductsStatus.failure && state.error != null) {
            showErrorSnack(context, state.error!);
          }
          if (state.importResult != null) {
            _showImportResult(context, state.importResult!);
          }
          if (state.exportedCsv != null) {
            _showExport(context, state.exportedCsv!);
          }
        },
        builder: (context, state) {
          switch (state.status) {
            case ProductsStatus.initial:
            case ProductsStatus.loading:
              return const LoadingView();
            case ProductsStatus.failure:
              return ErrorView(
                state.error ?? 'Failed to load products',
                onRetry: () =>
                    context.read<ProductsBloc>().add(ProductsRequested(catalogId)),
              );
            case ProductsStatus.loaded:
              if (state.products.isEmpty) {
                return const EmptyView('No products yet — add one or import a CSV.');
              }
              return SingleChildScrollView(
                padding: const EdgeInsets.all(16),
                child: DataTable(
                  columns: const [
                    DataColumn(label: Text('Title')),
                    DataColumn(label: Text('Category')),
                    DataColumn(label: Text('Status')),
                    DataColumn(label: Text('Variants')),
                    DataColumn(label: Text('Actions')),
                  ],
                  rows: state.products
                      .map((p) => DataRow(cells: [
                            DataCell(Text(p.title)),
                            DataCell(Text(p.category)),
                            DataCell(StatusChip(p.status)),
                            DataCell(Text(p.variants
                                .map((v) => '${v.sku} ${formatMoney(v.price)}')
                                .join('\n'))),
                            DataCell(Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                IconButton(
                                  tooltip: 'Edit',
                                  icon: const Icon(Icons.edit, size: 18),
                                  onPressed: () => _promptEditor(context, p),
                                ),
                                if (p.status == 'ARCHIVED')
                                  IconButton(
                                    tooltip: 'Restore',
                                    icon: const Icon(Icons.unarchive, size: 18),
                                    onPressed: () => context
                                        .read<ProductsBloc>()
                                        .add(ProductRestoreRequested(p.id)),
                                  )
                                else
                                  IconButton(
                                    tooltip: 'Archive',
                                    icon: const Icon(Icons.archive, size: 18),
                                    onPressed: () => context
                                        .read<ProductsBloc>()
                                        .add(ProductArchiveRequested(p.id)),
                                  ),
                              ],
                            )),
                          ]))
                      .toList(),
                ),
              );
          }
        },
      ),
    );
  }

  Future<void> _promptImport(BuildContext context) async {
    final bloc = context.read<ProductsBloc>();
    final controller = TextEditingController();
    final csv = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Import products (CSV)'),
        content: SizedBox(
          width: 560,
          child: TextField(
            controller: controller,
            maxLines: 12,
            decoration: const InputDecoration(
              hintText: 'Paste CSV — one row per variant, upserted by SKU',
              border: OutlineInputBorder(),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, controller.text),
            child: const Text('Import'),
          ),
        ],
      ),
    );
    if (csv != null && csv.trim().isNotEmpty) {
      bloc.add(ProductsImportRequested(csv));
    }
  }

  void _showImportResult(BuildContext context, ImportResult result) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Import result'),
        content: SizedBox(
          width: 480,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Created: ${result.created} · Updated: ${result.updated}'),
              if (result.errors.isNotEmpty) ...[
                const SizedBox(height: 12),
                const Text('Row errors', style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 4),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 240),
                  child: ListView(
                    shrinkWrap: true,
                    children: result.errors
                        .map((e) => Text('Row ${e.row}: ${e.message}',
                            style: const TextStyle(color: Colors.red)))
                        .toList(),
                  ),
                ),
              ],
            ],
          ),
        ),
        actions: [
          FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }

  void _showExport(BuildContext context, String csv) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Exported CSV'),
        content: SizedBox(
          width: 640,
          child: TextField(
            controller: TextEditingController(text: csv),
            readOnly: true,
            maxLines: 16,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            decoration: const InputDecoration(border: OutlineInputBorder()),
          ),
        ),
        actions: [
          FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }

  Future<void> _promptEditor(BuildContext context, Product? existing) async {
    final bloc = context.read<ProductsBloc>();
    final draft = await showDialog<ProductDraft>(
      context: context,
      builder: (ctx) => _ProductEditorDialog(existing: existing),
    );
    if (draft == null) return;
    if (existing == null) {
      bloc.add(ProductCreateRequested(draft));
    } else {
      bloc.add(ProductUpdateRequested(existing.id, draft));
    }
  }
}

/// Full authoring form: title/desc/category/status/media/attributes/metadata/
/// sortOrder + a variants editor (sku/label/price/options/weight/barcode).
class _ProductEditorDialog extends StatefulWidget {
  const _ProductEditorDialog({this.existing});

  final Product? existing;

  @override
  State<_ProductEditorDialog> createState() => _ProductEditorDialogState();
}

class _VariantDraftRow {
  _VariantDraftRow(Variant? v)
      : sku = TextEditingController(text: v?.sku ?? ''),
        label = TextEditingController(text: v?.label ?? ''),
        priceMinor = TextEditingController(
            text: v?.price == null ? '' : '${v!.price!.amountMinor}'),
        options = TextEditingController(text: _pairs(v?.options ?? const {})),
        weight = TextEditingController(text: v?.weightGrams == null ? '' : '${v!.weightGrams}'),
        barcode = TextEditingController(text: v?.barcode ?? '');

  final TextEditingController sku;
  final TextEditingController label;
  final TextEditingController priceMinor;
  final TextEditingController options;
  final TextEditingController weight;
  final TextEditingController barcode;

  static String _pairs(Map<String, String> map) =>
      map.entries.map((e) => '${e.key}=${e.value}').join(', ');
}

class _ProductEditorDialogState extends State<_ProductEditorDialog> {
  late final TextEditingController _title =
      TextEditingController(text: widget.existing?.title ?? '');
  late final TextEditingController _description =
      TextEditingController(text: widget.existing?.description ?? '');
  late final TextEditingController _category =
      TextEditingController(text: widget.existing?.category ?? '');
  late final TextEditingController _sortOrder =
      TextEditingController(text: '${widget.existing?.sortOrder ?? 0}');
  late final TextEditingController _media =
      TextEditingController(text: (widget.existing?.media ?? []).join('\n'));
  late final TextEditingController _attributes = TextEditingController(
      text: (widget.existing?.attributes ?? {})
          .entries
          .map((e) => '${e.key}=${e.value}')
          .join('\n'));
  late final TextEditingController _metadata = TextEditingController(
      text: (widget.existing?.metadata ?? {})
          .entries
          .map((e) => '${e.key}=${e.value}')
          .join('\n'));
  late final List<_VariantDraftRow> _variants = widget.existing == null ||
          widget.existing!.variants.isEmpty
      ? [_VariantDraftRow(null)]
      : widget.existing!.variants.map(_VariantDraftRow.new).toList();

  static Map<String, String> _parsePairs(String raw) {
    final out = <String, String>{};
    for (final line in raw.split(RegExp(r'[\n,]'))) {
      final idx = line.indexOf('=');
      if (idx <= 0) continue;
      out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    // Variant prices are entered in MINOR units in the store currency — the
    // console never does money arithmetic, it just passes minor units through.
    return AlertDialog(
      title: Text(widget.existing == null ? 'New product' : 'Edit product'),
      content: SizedBox(
        width: 720,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                Expanded(
                  flex: 2,
                  child: TextField(
                    controller: _title,
                    decoration: const InputDecoration(labelText: 'Title'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _category,
                    decoration: const InputDecoration(labelText: 'Category'),
                  ),
                ),
                const SizedBox(width: 12),
                SizedBox(
                  width: 100,
                  child: TextField(
                    controller: _sortOrder,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Sort'),
                  ),
                ),
              ]),
              const SizedBox(height: 12),
              TextField(
                controller: _description,
                maxLines: 3,
                decoration: const InputDecoration(labelText: 'Description'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _media,
                maxLines: 2,
                decoration: const InputDecoration(
                  labelText: 'Media URLs (one per line)',
                ),
              ),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _attributes,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: 'Attributes (key=value per line)',
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _metadata,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: 'Metadata (key=value per line)',
                    ),
                  ),
                ),
              ]),
              const SizedBox(height: 16),
              Row(
                children: [
                  Text('Variants', style: Theme.of(context).textTheme.titleSmall),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => setState(() => _variants.add(_VariantDraftRow(null))),
                    icon: const Icon(Icons.add),
                    label: const Text('Add variant'),
                  ),
                ],
              ),
              ..._variants.map((v) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Row(children: [
                      Expanded(
                        child: TextField(
                          controller: v.sku,
                          decoration: const InputDecoration(labelText: 'SKU'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextField(
                          controller: v.label,
                          decoration: const InputDecoration(labelText: 'Label'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 130,
                        child: TextField(
                          controller: v.priceMinor,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(
                            labelText: 'Price (minor)',
                            helperText: '1999 = 19.99',
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextField(
                          controller: v.options,
                          decoration: const InputDecoration(labelText: 'Options k=v,…'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 90,
                        child: TextField(
                          controller: v.weight,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: 'Weight g'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 110,
                        child: TextField(
                          controller: v.barcode,
                          decoration: const InputDecoration(labelText: 'Barcode'),
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete_outline, size: 18),
                        onPressed: _variants.length == 1
                            ? null
                            : () => setState(() => _variants.remove(v)),
                      ),
                    ]),
                  )),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _submit, child: const Text('Save')),
      ],
    );
  }

  void _submit() {
    final storeCurrency = _storeCurrency();
    final variants = _variants
        .where((v) => v.sku.text.trim().isNotEmpty)
        .map((v) => Variant(
              sku: v.sku.text.trim(),
              label: v.label.text.trim(),
              price: v.priceMinor.text.trim().isEmpty
                  ? null
                  : Money(
                      amountMinor: int.tryParse(v.priceMinor.text.trim()) ?? 0,
                      currency: storeCurrency,
                    ),
              options: _parsePairs(v.options.text),
              weightGrams:
                  v.weight.text.trim().isEmpty ? null : int.tryParse(v.weight.text.trim()),
              barcode: v.barcode.text.trim().isEmpty ? null : v.barcode.text.trim(),
            ))
        .toList();
    Navigator.pop(
      context,
      ProductDraft(
        title: _title.text.trim(),
        description: _description.text.trim(),
        category: _category.text.trim(),
        status: widget.existing?.status.isNotEmpty == true ? widget.existing!.status : null,
        sortOrder: int.tryParse(_sortOrder.text.trim()) ?? 0,
        attributes: _parsePairs(_attributes.text),
        media: _media.text
            .split('\n')
            .map((l) => l.trim())
            .where((l) => l.isNotEmpty)
            .toList(),
        metadata: _parsePairs(_metadata.text),
        variants: variants,
      ),
    );
  }

  String _storeCurrency() {
    // The store's default currency travels on prices; StoreContext keeps the
    // acting store, and the settings page shows the currency. We keep the last
    // known currency in the page via an inherited default of USD when unknown.
    return sl<StoreContext>().storeCurrency ?? 'USD';
  }
}
