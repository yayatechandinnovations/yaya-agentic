import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../api/api_config.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/product.dart';
import '../shared/admin_shared.dart';
import 'image_picker_web.dart';

/// The full per-product editor — every field the paged table omits (long
/// description, category, arbitrary attributes, variants, image gallery). Save
/// is a single-row create/update; it never bumps the catalog config version.
/// See commerce-redesign-authoring-rendering-paypal.md Pillar A §A.5.
class ProductEditorDialog extends ConsumerStatefulWidget {
  const ProductEditorDialog({
    super.key,
    required this.catalogId,
    required this.currency,
    this.productId,          // null → create
  });

  final String catalogId;
  final String currency;
  final String? productId;

  @override
  ConsumerState<ProductEditorDialog> createState() => _ProductEditorDialogState();
}

class _ProductEditorDialogState extends ConsumerState<ProductEditorDialog> {
  final _sku = TextEditingController();
  final _title = TextEditingController();
  final _shortDesc = TextEditingController();
  final _longDesc = TextEditingController();
  final _category = TextEditingController();
  final _price = TextEditingController();
  final _inventory = TextEditingController();
  final _detailUrl = TextEditingController();
  String _availability = 'IN_STOCK';
  final List<String> _images = [];
  final List<_AttrRow> _attrs = [];
  final List<_VariantRow> _variants = [];

  bool _loading = false;
  bool _saving = false;

  bool get _isNew => widget.productId == null;

  @override
  void initState() {
    super.initState();
    if (!_isNew) _load();
  }

  @override
  void dispose() {
    _sku.dispose(); _title.dispose(); _shortDesc.dispose(); _longDesc.dispose();
    _category.dispose(); _price.dispose(); _inventory.dispose(); _detailUrl.dispose();
    for (final a in _attrs) { a.dispose(); }
    for (final v in _variants) { v.dispose(); }
    super.dispose();
  }

  String? get _tenant => ref.read(currentTenantOrNull);

  Future<void> _load() async {
    final tenant = _tenant;
    if (tenant == null) return;
    setState(() => _loading = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final p = await api.getProduct(
        tenant: tenant, catalogId: widget.catalogId, productId: widget.productId!);
      if (!mounted) return;
      setState(() {
        _sku.text = p.productId;
        _title.text = p.title;
        _shortDesc.text = p.shortDescription ?? '';
        _longDesc.text = p.longDescription ?? '';
        _category.text = p.category ?? '';
        _price.text = (p.priceMinor / 100).toStringAsFixed(2);
        _inventory.text = p.inventory?.toString() ?? '';
        _detailUrl.text = p.detailUrl ?? '';
        _availability = p.availability;
        _images.addAll(p.imageUrls);
        p.attributes.forEach((k, v) => _attrs.add(_AttrRow(k, v)));
        for (final v in p.variants) {
          _variants.add(_VariantRow.from(v, widget.currency));
        }
        _loading = false;
      });
    } catch (e) {
      if (mounted) { setState(() => _loading = false); showSnack(context, formatError(e), error: true); }
    }
  }

  Future<void> _addImage() async {
    final tenant = _tenant;
    if (tenant == null) return;
    try {
      final picked = await pickImageFromBrowser();
      if (picked == null) return;
      final api = await ref.read(adminApiProvider.future);
      final cfg = await ref.read(apiConfigProvider.future);
      final relative = await api.uploadCommerceImage(
        tenant: tenant, bytes: picked.bytes, filename: picked.name, contentType: picked.contentType);
      if (relative.isNotEmpty && mounted) setState(() => _images.add('${cfg.baseUrl}$relative'));
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _save() async {
    final tenant = _tenant;
    if (tenant == null) return;
    final sku = _sku.text.trim();
    final title = _title.text.trim();
    if (sku.isEmpty || title.isEmpty) {
      showSnack(context, 'SKU and name are required', error: true);
      return;
    }
    final priceMinor = ((double.tryParse(_price.text.trim()) ?? 0) * 100).round();
    final inv = int.tryParse(_inventory.text.trim());
    final attrs = <String, String>{};
    for (final a in _attrs) {
      if (a.key.text.trim().isNotEmpty) attrs[a.key.text.trim()] = a.value.text.trim();
    }
    final variants = <Map<String, dynamic>>[];
    for (final v in _variants) {
      final id = v.id.text.trim();
      if (id.isEmpty) continue;
      variants.add({
        'variantId': id,
        'label': v.label.text.trim(),
        'priceMinor': ((double.tryParse(v.price.text.trim()) ?? 0) * 100).round(),
        'currency': widget.currency,
        'sku': v.sku.text.trim(),
        'availability': v.availability,
      });
    }
    final body = <String, dynamic>{
      'productId': sku,
      'title': title,
      'shortDescription': _shortDesc.text.trim(),
      'longDescription': _longDesc.text.trim(),
      'priceMinor': priceMinor,
      'currency': widget.currency,
      'category': _category.text.trim(),
      'attributes': attrs,
      'imageUrls': _images,
      'variants': variants,
      'availability': _availability,
      if (inv != null) 'inventory': inv,
      'detailUrl': _detailUrl.text.trim(),
    };
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      if (_isNew) {
        await api.createProduct(tenant: tenant, catalogId: widget.catalogId, body: body);
      } else {
        await api.updateProduct(
          tenant: tenant, catalogId: widget.catalogId, productId: widget.productId!, body: body);
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) { setState(() => _saving = false); showSnack(context, formatError(e), error: true); }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640, maxHeight: 720),
        child: _loading
            ? const SizedBox(height: 200, child: Center(child: CircularProgressIndicator()))
            : Column(mainAxisSize: MainAxisSize.min, children: [
                _header(context),
                const Divider(height: 1),
                Expanded(child: SingleChildScrollView(
                  padding: const EdgeInsets.all(20),
                  child: _form(context),
                )),
                const Divider(height: 1),
                _actions(context),
              ]),
      ),
    );
  }

  Widget _header(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 12, 16),
        child: Row(children: [
          Text(_isNew ? 'New product' : 'Edit product',
              style: Theme.of(context).textTheme.titleMedium),
          const Spacer(),
          IconButton(onPressed: () => Navigator.pop(context, false), icon: const Icon(Icons.close)),
        ]),
      );

  Widget _form(BuildContext context) {
    InputDecoration d(String l) => InputDecoration(labelText: l, isDense: true, border: const OutlineInputBorder());
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Expanded(child: TextField(controller: _sku, enabled: _isNew, decoration: d('SKU / code'))),
        const SizedBox(width: 12),
        Expanded(flex: 2, child: TextField(controller: _title, decoration: d('name'))),
      ]),
      const SizedBox(height: 12),
      TextField(controller: _shortDesc, decoration: d('short description'), maxLines: 2),
      const SizedBox(height: 12),
      TextField(controller: _longDesc, decoration: d('long description'), maxLines: 4),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(child: TextField(
          controller: _price,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: d('price').copyWith(prefixText: widget.currency == 'DOP' ? r'RD$ ' : r'$ '),
        )),
        const SizedBox(width: 12),
        Expanded(child: TextField(controller: _inventory,
            keyboardType: TextInputType.number, decoration: d('inventory'))),
        const SizedBox(width: 12),
        Expanded(child: DropdownButtonFormField<String>(
          initialValue: _availability,
          decoration: d('availability'),
          items: const [
            DropdownMenuItem(value: 'IN_STOCK', child: Text('In stock')),
            DropdownMenuItem(value: 'LOW', child: Text('Low')),
            DropdownMenuItem(value: 'OUT', child: Text('Out')),
            DropdownMenuItem(value: 'PREORDER', child: Text('Preorder')),
          ],
          onChanged: (v) => setState(() => _availability = v ?? 'IN_STOCK'),
        )),
      ]),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(child: TextField(controller: _category, decoration: d('category'))),
        const SizedBox(width: 12),
        Expanded(flex: 2, child: TextField(controller: _detailUrl, decoration: d('detail URL (optional)'))),
      ]),
      const SizedBox(height: 20),
      _sectionLabel(context, 'Images'),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final url in _images) _Thumb(url: url, onRemove: () => setState(() => _images.remove(url))),
        _AddTile(icon: Icons.add_a_photo_outlined, onTap: _addImage),
      ]),
      const SizedBox(height: 20),
      _sectionLabel(context, 'Attributes (facet chips)'),
      const SizedBox(height: 8),
      ..._attrs.map((a) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(children: [
          Expanded(child: TextField(controller: a.key, decoration: d('key'))),
          const SizedBox(width: 8),
          Expanded(child: TextField(controller: a.value, decoration: d('value'))),
          IconButton(icon: const Icon(Icons.remove_circle_outline),
              onPressed: () => setState(() { _attrs.remove(a); a.dispose(); })),
        ]),
      )),
      Align(alignment: Alignment.centerLeft, child: TextButton.icon(
        onPressed: () => setState(() => _attrs.add(_AttrRow('', ''))),
        icon: const Icon(Icons.add), label: const Text('Add attribute'))),
      const SizedBox(height: 12),
      _sectionLabel(context, 'Variants'),
      const SizedBox(height: 8),
      ..._variants.map((v) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(children: [
          Expanded(child: TextField(controller: v.id, decoration: d('variant id'))),
          const SizedBox(width: 8),
          Expanded(child: TextField(controller: v.label, decoration: d('label'))),
          const SizedBox(width: 8),
          Expanded(child: TextField(controller: v.price,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: d('price'))),
          IconButton(icon: const Icon(Icons.remove_circle_outline),
              onPressed: () => setState(() { _variants.remove(v); v.dispose(); })),
        ]),
      )),
      Align(alignment: Alignment.centerLeft, child: TextButton.icon(
        onPressed: () => setState(() => _variants.add(_VariantRow.empty())),
        icon: const Icon(Icons.add), label: const Text('Add variant'))),
    ]);
  }

  Widget _sectionLabel(BuildContext context, String text) =>
      Align(alignment: Alignment.centerLeft,
          child: Text(text, style: Theme.of(context).textTheme.labelLarge));

  Widget _actions(BuildContext context) => Padding(
        padding: const EdgeInsets.all(16),
        child: Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          TextButton(onPressed: _saving ? null : () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          const SizedBox(width: 8),
          FilledButton.icon(
            onPressed: _saving ? null : _save,
            icon: _saving
                ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.save_outlined),
            label: Text(_isNew ? 'Create' : 'Save'),
          ),
        ]),
      );
}

class _AttrRow {
  _AttrRow(String k, String v) : key = TextEditingController(text: k), value = TextEditingController(text: v);
  final TextEditingController key;
  final TextEditingController value;
  void dispose() { key.dispose(); value.dispose(); }
}

class _VariantRow {
  _VariantRow(this.id, this.label, this.price, this.sku, this.availability);

  factory _VariantRow.empty() => _VariantRow(
      TextEditingController(), TextEditingController(), TextEditingController(),
      TextEditingController(), 'IN_STOCK');

  factory _VariantRow.from(VariantView v, String currency) => _VariantRow(
      TextEditingController(text: v.variantId),
      TextEditingController(text: v.label),
      TextEditingController(text: (v.priceMinor / 100).toStringAsFixed(2)),
      TextEditingController(text: v.sku ?? ''),
      v.availability ?? 'IN_STOCK');

  final TextEditingController id;
  final TextEditingController label;
  final TextEditingController price;
  final TextEditingController sku;
  String availability;
  void dispose() { id.dispose(); label.dispose(); price.dispose(); sku.dispose(); }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url, required this.onRemove});
  final String url;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return Stack(clipBehavior: Clip.none, children: [
      ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: Image.network(url, width: 64, height: 64, fit: BoxFit.cover,
            errorBuilder: (_, _, _) => Container(width: 64, height: 64,
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                child: const Icon(Icons.broken_image_outlined, size: 18))),
      ),
      Positioned(right: -6, top: -6, child: IconButton(
        iconSize: 16, onPressed: onRemove, icon: const Icon(Icons.cancel),
        style: IconButton.styleFrom(backgroundColor: Theme.of(context).colorScheme.surface))),
    ]);
  }
}

class _AddTile extends StatelessWidget {
  const _AddTile({required this.icon, required this.onTap});
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Container(
        width: 64, height: 64,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(6), border: Border.all(color: scheme.outlineVariant)),
        child: Icon(icon, color: scheme.outline, size: 20),
      ),
    );
  }
}
