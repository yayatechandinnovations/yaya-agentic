import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../api/api_config.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/product_catalog.dart';
import '../shared/admin_shared.dart';
import 'image_picker_web.dart';

final productCatalogsProvider =
    FutureProvider.autoDispose<List<ProductCatalogResponse>>((ref) async {
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listProductCatalogs(tenant: tenant);
});

/// Currencies offered in the admin. Kept short on purpose (the two markets we
/// support today); extend as regions come online.
const _currencies = {'USD': 'US Dollar (USD)', 'DOP': 'Dominican Peso (DOP)'};

const _authForwardingOptions = {
  'SERVICE_TOKEN': 'Service token (platform → merchant)',
  'PRINCIPAL_TOKEN': 'Shopper token (forward the caller)',
  'NONE': 'None',
};

/// A product being edited in the inline builder. Plain controllers, disposed
/// with the screen. Maps to the normalized Product JSON on save.
class _ProductDraft {
  _ProductDraft();

  /// Rehydrate a draft from a persisted product JSON (edit-in-place).
  factory _ProductDraft.from(Map<String, dynamic> p) {
    final d = _ProductDraft();
    final id = p['id'];
    d.code.text = id is Map ? (id['value']?.toString() ?? '') : (id?.toString() ?? '');
    d.name.text = p['title']?.toString() ?? '';
    d.description.text = p['shortDescription']?.toString() ?? '';
    final price = p['price'];
    final minor = price is Map ? ((price['amountMinor'] as num?)?.toInt() ?? 0) : 0;
    d.price.text = (minor / 100).toStringAsFixed(2);
    final attrs = (p['attributes'] as Map?) ?? const {};
    d.composition.text = attrs['composition']?.toString() ?? '';
    d.inventory.text = attrs['inventory']?.toString() ?? '0';
    for (final u in (p['imageUrls'] as List?) ?? const []) {
      d.imageUrls.add(u.toString());
    }
    return d;
  }

  final code = TextEditingController();
  final name = TextEditingController();
  final description = TextEditingController();
  final composition = TextEditingController();
  final price = TextEditingController();
  final inventory = TextEditingController(text: '0');
  final List<String> imageUrls = [];

  void dispose() {
    code.dispose();
    name.dispose();
    description.dispose();
    composition.dispose();
    price.dispose();
    inventory.dispose();
  }

  Map<String, dynamic> toJson(String currency) {
    final priceMinor = ((double.tryParse(price.text.trim()) ?? 0) * 100).round();
    final inv = int.tryParse(inventory.text.trim()) ?? 0;
    final attrs = <String, String>{};
    if (composition.text.trim().isNotEmpty) attrs['composition'] = composition.text.trim();
    attrs['inventory'] = '$inv';
    return {
      'id': {'value': code.text.trim()},
      'title': name.text.trim(),
      'shortDescription': description.text.trim(),
      'price': {'amountMinor': priceMinor, 'currency': currency},
      'imageUrls': List<String>.from(imageUrls),
      'category': '',
      'attributes': attrs,
      'variants': const [],
      'availability': inv <= 0 ? 'OUT' : (inv < 5 ? 'LOW' : 'IN_STOCK'),
    };
  }
}

class ProductCatalogsScreen extends ConsumerStatefulWidget {
  const ProductCatalogsScreen({super.key});
  @override
  ConsumerState<ProductCatalogsScreen> createState() => _State();
}

class _State extends ConsumerState<ProductCatalogsScreen> {
  final _idCtrl = TextEditingController();
  final _nameCtrl = TextEditingController();
  String _currency = 'USD';
  String? _catalogImageUrl;
  String _kind = 'DATABASE';
  bool _saving = false;
  bool _uploadingCatalogImage = false;
  int? _cloningFromVersion;

  final List<_ProductDraft> _products = [_ProductDraft()];

  // HTTP API fields
  final _listPathCtrl = TextEditingController(text: '/products');
  final _getPathCtrl = TextEditingController(text: '/products/{productId}');
  String _authForwarding = 'SERVICE_TOKEN';
  final _itemsPathCtrl = TextEditingController(text: r'$.data');
  final _pIdCtrl = TextEditingController(text: r'$.sku');
  final _pTitleCtrl = TextEditingController(text: r'$.name');
  final _pPriceCtrl = TextEditingController(text: r'$.cents');
  final _pCategoryCtrl = TextEditingController(text: r'$.category');
  final _pImagesCtrl = TextEditingController(text: r'$.images');
  final _pAttrsCtrl = TextEditingController(text: r'$.attributes');

  @override
  void dispose() {
    _idCtrl.dispose();
    _nameCtrl.dispose();
    for (final p in _products) { p.dispose(); }
    _listPathCtrl.dispose();
    _getPathCtrl.dispose();
    _itemsPathCtrl.dispose();
    _pIdCtrl.dispose();
    _pTitleCtrl.dispose();
    _pPriceCtrl.dispose();
    _pCategoryCtrl.dispose();
    _pImagesCtrl.dispose();
    _pAttrsCtrl.dispose();
    super.dispose();
  }

  Future<String?> _pickAndUpload() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return null;
    }
    final picked = await pickImageFromBrowser();
    if (picked == null) return null;
    final api = await ref.read(adminApiProvider.future);
    final cfg = await ref.read(apiConfigProvider.future);
    final relative = await api.uploadCommerceImage(
      tenant: tenant,
      bytes: picked.bytes,
      filename: picked.name,
      contentType: picked.contentType,
    );
    return relative.isEmpty ? null : '${cfg.baseUrl}$relative';
  }

  Future<void> _uploadCatalogImage() async {
    setState(() => _uploadingCatalogImage = true);
    try {
      final url = await _pickAndUpload();
      if (url != null && mounted) setState(() => _catalogImageUrl = url);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _uploadingCatalogImage = false);
    }
  }

  Future<void> _uploadProductImage(_ProductDraft draft) async {
    try {
      final url = await _pickAndUpload();
      if (url != null && mounted) setState(() => draft.imageUrls.add(url));
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Map<String, dynamic> _buildLocation() {
    // Managed catalog: products live in the products table, authored on the
    // catalog's Products page — the location just names the row set (backend
    // defaults logicalName to the catalog id).
    if (_kind == 'DATABASE') return const {};
    if (_kind == 'INLINE') {
      return {
        'products': _products
            .where((p) => p.code.text.trim().isNotEmpty && p.name.text.trim().isNotEmpty)
            .map((p) => p.toJson(_currency))
            .toList(),
      };
    }
    Map<String, dynamic> spec(String path) => {
          'method': 'GET',
          'urlTemplate': path.trim(),
          'headerTemplates': const {},
          'body': null,
          'response': {'jsonPath': r'$', 'headerOutputs': const {}},
          'authForwarding': _authForwarding,
        };
    final fieldPaths = <String, String>{};
    void put(String key, TextEditingController c) {
      if (c.text.trim().isNotEmpty) fieldPaths[key] = c.text.trim();
    }
    put('id', _pIdCtrl);
    put('title', _pTitleCtrl);
    put('price.amountMinor', _pPriceCtrl);
    put('category', _pCategoryCtrl);
    put('imageUrls', _pImagesCtrl);
    put('attributes', _pAttrsCtrl);
    return {
      'listSpec': spec(_listPathCtrl.text),
      'getSpec': spec(_getPathCtrl.text),
      'projection': {'itemsPath': _itemsPathCtrl.text.trim(), 'fieldPaths': fieldPaths},
    };
  }

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    if (_idCtrl.text.trim().isEmpty || _nameCtrl.text.trim().isEmpty) {
      showSnack(context, 'id and name are required', error: true);
      return;
    }
    if (_kind == 'INLINE' &&
        !_products.any((p) => p.code.text.trim().isNotEmpty && p.name.text.trim().isNotEmpty)) {
      showSnack(context, 'add at least one product (code + name)', error: true);
      return;
    }
    final wasNew = _cloningFromVersion == null;
    final catalogId = _idCtrl.text.trim();
    final kind = _kind;
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.createProductCatalog(CreateProductCatalogRequest(
        tenant: tenant,
        id: catalogId,
        displayName: _nameCtrl.text.trim(),
        currency: _currency,
        imageUrl: _catalogImageUrl,
        locationKind: _kind,
        location: _buildLocation(),
      ));
      if (!mounted) return;
      showSnack(context, wasNew ? 'catalog created' : 'saved as v${_cloningFromVersion! + 1}');
      ref.invalidate(productCatalogsProvider);
      _reset();
      // A managed catalog has nowhere to add products in this form — take the
      // operator straight to the product workspace after creating it.
      if (wasNew && kind == 'DATABASE' && mounted) {
        context.go('/admin/product-catalogs/$catalogId/products');
      }
    } catch (e) {
      if (!mounted) return;
      showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _reset() {
    setState(() {
      _cloningFromVersion = null;
      _idCtrl.clear();
      _nameCtrl.clear();
      _currency = 'USD';
      _catalogImageUrl = null;
      _kind = 'DATABASE';
      for (final p in _products) { p.dispose(); }
      _products
        ..clear()
        ..add(_ProductDraft());
    });
  }

  /// Loads the full catalog (incl. products / mapping) and populates the form
  /// for edit-in-place. Saves as the next version on submit.
  Future<void> _loadForEdit(ProductCatalogResponse c) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final d = await api.getProductCatalog(tenant: tenant, id: c.id);
      if (!mounted) return;
      setState(() {
        _idCtrl.text = d.id;
        _nameCtrl.text = d.displayName;
        _currency = _currencies.containsKey(d.currency) ? d.currency : 'USD';
        _catalogImageUrl = d.imageUrl;
        _kind = switch (d.locationKind) {
          'HTTP_API' => 'HTTP_API',
          'DATABASE' => 'DATABASE',
          _ => 'INLINE',
        };
        for (final p in _products) { p.dispose(); }
        _products.clear();
        _products.add(_ProductDraft());   // keep one draft alive for the inline editor path
        if (_kind == 'INLINE') {
          _products.clear();
          for (final p in (d.location['products'] as List?) ?? const []) {
            if (p is Map) _products.add(_ProductDraft.from(p.cast<String, dynamic>()));
          }
          if (_products.isEmpty) _products.add(_ProductDraft());
        } else if (_kind == 'HTTP_API') {
          _loadHttp(d.location);
        }
        _cloningFromVersion = d.version;
      });
      if (mounted) showSnack(context, 'Editing ${d.id}@${d.version} → saves as v${d.version + 1}');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _convertToManaged(ProductCatalogResponse c) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.convertCatalogToManaged(tenant: tenant, id: c.id);
      if (!mounted) return;
      ref.invalidate(productCatalogsProvider);
      showSnack(context, '${c.id} converted to managed — edit products here');
      context.go('/admin/product-catalogs/${c.id}/products');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  void _loadHttp(Map<String, dynamic> location) {
    final list = (location['listSpec'] as Map?) ?? const {};
    final get = (location['getSpec'] as Map?) ?? const {};
    final proj = (location['projection'] as Map?) ?? const {};
    final fp = (proj['fieldPaths'] as Map?) ?? const {};
    _listPathCtrl.text = list['urlTemplate']?.toString() ?? '';
    _getPathCtrl.text = get['urlTemplate']?.toString() ?? '';
    final af = list['authForwarding']?.toString();
    _authForwarding = _authForwardingOptions.containsKey(af) ? af! : 'SERVICE_TOKEN';
    _itemsPathCtrl.text = proj['itemsPath']?.toString() ?? r'$.data';
    _pIdCtrl.text = fp['id']?.toString() ?? '';
    _pTitleCtrl.text = fp['title']?.toString() ?? '';
    _pPriceCtrl.text = fp['price.amountMinor']?.toString() ?? '';
    _pCategoryCtrl.text = fp['category']?.toString() ?? '';
    _pImagesCtrl.text = fp['imageUrls']?.toString() ?? '';
    _pAttrsCtrl.text = fp['attributes']?.toString() ?? '';
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Product catalogs');
    }
    final catalogs = ref.watch(productCatalogsProvider);
    return AdminTwoPane(
      title: 'Product catalogs',
      subtitle: 'Build a catalog and its products, or map a merchant store API. '
          'Bind a catalog to a profile under Profiles → Commerce.',
      list: catalogs.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Padding(
            padding: const EdgeInsets.all(16),
            child: Text(formatError(e),
                style: TextStyle(color: Theme.of(context).colorScheme.error))),
        data: (list) => list.isEmpty
            ? const Padding(padding: EdgeInsets.all(16), child: Text('No catalogs yet.'))
            : ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: list.length,
                separatorBuilder: (_, _) => const SizedBox(height: 8),
                itemBuilder: (_, i) => _CatalogCard(
                  catalog: list[i],
                  onCloneEdit: () => _loadForEdit(list[i]),
                  onManageProducts: () =>
                      context.go('/admin/product-catalogs/${list[i].id}/products'),
                  onConvert: () => _convertToManaged(list[i]),
                ),
              ),
      ),
      form: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
                _cloningFromVersion == null
                    ? 'Create product catalog'
                    : 'New version of ${_idCtrl.text} → v${_cloningFromVersion! + 1}',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              _CatalogImagePicker(
                url: _catalogImageUrl,
                uploading: _uploadingCatalogImage,
                onPick: _uploadCatalogImage,
                onClear: () => setState(() => _catalogImageUrl = null),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(children: [
                  TextField(controller: _idCtrl,
                      decoration: const InputDecoration(labelText: 'catalog id (kebab)')),
                  const SizedBox(height: 8),
                  TextField(controller: _nameCtrl,
                      decoration: const InputDecoration(labelText: 'catalog name')),
                ]),
              ),
            ]),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: _currency,
              decoration: const InputDecoration(labelText: 'currency'),
              items: _currencies.entries
                  .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
                  .toList(),
              onChanged: (v) => setState(() => _currency = v ?? 'USD'),
            ),
            const SizedBox(height: 16),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'DATABASE', icon: Icon(Icons.table_rows_outlined),
                    label: Text('Managed')),
                ButtonSegment(value: 'INLINE', icon: Icon(Icons.inventory_2_outlined),
                    label: Text('Inline')),
                ButtonSegment(value: 'HTTP_API', icon: Icon(Icons.api),
                    label: Text('Merchant API')),
              ],
              selected: {_kind},
              onSelectionChanged: (s) => setState(() => _kind = s.first),
            ),
            const SizedBox(height: 16),
            if (_kind == 'DATABASE')
              _managedNote(context)
            else if (_kind == 'INLINE')
              _inlineEditor(context)
            else
              _httpEditor(context),
            const SizedBox(height: 20),
            Row(mainAxisAlignment: MainAxisAlignment.end, children: [
              if (_cloningFromVersion != null) ...[
                TextButton(onPressed: _saving ? null : _reset, child: const Text('Cancel')),
                const SizedBox(width: 8),
              ],
              FilledButton.icon(
                onPressed: _saving ? null : _save,
                icon: _saving
                    ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.save_outlined),
                label: Text(_cloningFromVersion == null
                    ? 'Create catalog'
                    : 'Save as v${_cloningFromVersion! + 1}'),
              ),
            ]),
          ],
        ),
      ),
    );
  }

  Widget _inlineEditor(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(children: [
          Text('Products', style: Theme.of(context).textTheme.labelLarge),
          const Spacer(),
          Text('${_products.length}', style: Theme.of(context).textTheme.labelMedium),
        ]),
        const SizedBox(height: 8),
        ...List.generate(_products.length, (i) => _ProductEditor(
              key: ValueKey(_products[i]),
              index: i,
              draft: _products[i],
              currency: _currency,
              onRemove: _products.length == 1
                  ? null
                  : () => setState(() { _products.removeAt(i).dispose(); }),
              onAddImage: () => _uploadProductImage(_products[i]),
              onRemoveImage: (url) => setState(() => _products[i].imageUrls.remove(url)),
            )),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: () => setState(() => _products.add(_ProductDraft())),
            icon: const Icon(Icons.add),
            label: const Text('Add product'),
          ),
        ),
      ],
    );
  }

  Widget _managedNote(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Row(children: [
        Icon(Icons.table_rows_outlined, color: scheme.primary),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            _cloningFromVersion == null
                ? 'Managed catalog: create it, then add products on its Products page — a paged '
                    'table with quick-add, CSV import/export, and a full editor per product.'
                : 'Managed catalog. Edit its products on the Products page (single-row edits, no '
                    'version bump). Saving here only updates catalog settings.',
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
          ),
        ),
        if (_cloningFromVersion != null) ...[
          const SizedBox(width: 8),
          FilledButton.tonalIcon(
            onPressed: () => context.go('/admin/product-catalogs/${_idCtrl.text.trim()}/products'),
            icon: const Icon(Icons.inventory_2_outlined, size: 18),
            label: const Text('Products'),
          ),
        ],
      ]),
    );
  }

  Widget _httpEditor(BuildContext context) {
    InputDecoration deco(String label, String help) =>
        InputDecoration(labelText: label, helperText: help, isDense: true);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Merchant store API', style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 8),
        TextField(controller: _listPathCtrl,
            decoration: deco('list path', 'e.g. /products (host comes from the tenant)')),
        const SizedBox(height: 8),
        TextField(controller: _getPathCtrl,
            decoration: deco('get-one path', 'e.g. /products/{productId}')),
        const SizedBox(height: 8),
        DropdownButtonFormField<String>(
          initialValue: _authForwarding,
          decoration: const InputDecoration(labelText: 'auth forwarding', isDense: true),
          items: _authForwardingOptions.entries
              .map((e) => DropdownMenuItem(value: e.key, child: Text(e.value)))
              .toList(),
          onChanged: (v) => setState(() => _authForwarding = v ?? 'SERVICE_TOKEN'),
        ),
        const SizedBox(height: 16),
        Text('Response mapping (JsonPath)', style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 8),
        TextField(controller: _itemsPathCtrl,
            decoration: deco('items path', r'array of products, e.g. $.data')),
        const SizedBox(height: 8),
        TextField(controller: _pIdCtrl, decoration: deco('product id', r'e.g. $.sku')),
        const SizedBox(height: 8),
        TextField(controller: _pTitleCtrl, decoration: deco('title', r'e.g. $.name')),
        const SizedBox(height: 8),
        TextField(controller: _pPriceCtrl,
            decoration: deco('price (minor units)', r'integer cents, e.g. $.cents')),
        const SizedBox(height: 8),
        TextField(controller: _pCategoryCtrl, decoration: deco('category', r'e.g. $.category')),
        const SizedBox(height: 8),
        TextField(controller: _pImagesCtrl, decoration: deco('images', r'array, e.g. $.images')),
        const SizedBox(height: 8),
        TextField(controller: _pAttrsCtrl, decoration: deco('attributes', r'object, e.g. $.attributes')),
      ],
    );
  }
}

// ---------------------------------------------------------------------------

class _ProductEditor extends StatelessWidget {
  const _ProductEditor({
    super.key,
    required this.index,
    required this.draft,
    required this.currency,
    required this.onRemove,
    required this.onAddImage,
    required this.onRemoveImage,
  });
  final int index;
  final _ProductDraft draft;
  final String currency;
  final VoidCallback? onRemove;
  final VoidCallback onAddImage;
  final ValueChanged<String> onRemoveImage;

  @override
  Widget build(BuildContext context) {
    final title = draft.name.text.trim().isEmpty ? 'Product ${index + 1}' : draft.name.text.trim();
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ExpansionTile(
        initiallyExpanded: index == 0,
        title: Text(title),
        subtitle: draft.code.text.trim().isEmpty ? null : Text(draft.code.text.trim()),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          Row(children: [
            Expanded(child: TextField(controller: draft.code,
                decoration: const InputDecoration(labelText: 'code / SKU', isDense: true))),
            const SizedBox(width: 8),
            Expanded(child: TextField(controller: draft.name,
                decoration: const InputDecoration(labelText: 'name', isDense: true))),
          ]),
          const SizedBox(height: 8),
          TextField(controller: draft.description, maxLines: 2,
              decoration: const InputDecoration(labelText: 'description', isDense: true)),
          const SizedBox(height: 8),
          TextField(controller: draft.composition, maxLines: 2,
              decoration: const InputDecoration(
                  labelText: 'composition / ingredients', isDense: true)),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: TextField(controller: draft.price,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                    labelText: 'price', prefixText: currency == 'DOP' ? 'RD\$ ' : '\$ ', isDense: true))),
            const SizedBox(width: 8),
            Expanded(child: TextField(controller: draft.inventory,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'initial inventory', isDense: true))),
          ]),
          const SizedBox(height: 12),
          Align(
            alignment: Alignment.centerLeft,
            child: Text('Images', style: Theme.of(context).textTheme.labelMedium),
          ),
          const SizedBox(height: 6),
          Wrap(spacing: 8, runSpacing: 8, children: [
            for (final url in draft.imageUrls) _Thumb(url: url, onRemove: () => onRemoveImage(url)),
            _AddImageTile(onTap: onAddImage),
          ]),
          const SizedBox(height: 8),
          if (onRemove != null)
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: onRemove,
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('Remove product'),
              ),
            ),
        ],
      ),
    );
  }
}

class _CatalogImagePicker extends StatelessWidget {
  const _CatalogImagePicker({
    required this.url, required this.uploading, required this.onPick, required this.onClear,
  });
  final String? url;
  final bool uploading;
  final VoidCallback onPick;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      width: 96,
      child: Column(children: [
        InkWell(
          onTap: uploading ? null : onPick,
          child: Container(
            width: 96, height: 96,
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: scheme.outlineVariant),
            ),
            clipBehavior: Clip.antiAlias,
            child: uploading
                ? const Center(child: CircularProgressIndicator(strokeWidth: 2))
                : (url == null
                    ? Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                        Icon(Icons.add_photo_alternate_outlined, color: scheme.outline),
                        const Text('cover', style: TextStyle(fontSize: 11)),
                      ])
                    : Image.network(url!, fit: BoxFit.cover,
                        errorBuilder: (_, _, _) => Icon(Icons.broken_image_outlined, color: scheme.outline))),
          ),
        ),
        if (url != null)
          TextButton(onPressed: onClear, child: const Text('remove', style: TextStyle(fontSize: 12))),
      ]),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url, required this.onRemove});
  final String url;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return Stack(children: [
      ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: Image.network(url, width: 64, height: 64, fit: BoxFit.cover,
            errorBuilder: (_, _, _) => Container(
                width: 64, height: 64,
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                child: const Icon(Icons.broken_image_outlined, size: 18))),
      ),
      Positioned(
        right: -6, top: -6,
        child: IconButton(
          iconSize: 16,
          onPressed: onRemove,
          icon: const Icon(Icons.cancel),
          style: IconButton.styleFrom(backgroundColor: Theme.of(context).colorScheme.surface),
        ),
      ),
    ]);
  }
}

class _AddImageTile extends StatelessWidget {
  const _AddImageTile({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Container(
        width: 64, height: 64,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: scheme.outlineVariant),
        ),
        child: Icon(Icons.add_a_photo_outlined, color: scheme.outline, size: 20),
      ),
    );
  }
}

class _CatalogCard extends StatelessWidget {
  const _CatalogCard({
    required this.catalog,
    required this.onCloneEdit,
    required this.onManageProducts,
    required this.onConvert,
  });
  final ProductCatalogResponse catalog;
  final VoidCallback onCloneEdit;
  final VoidCallback onManageProducts;
  final VoidCallback onConvert;

  String get _source => switch (catalog.locationKind) {
        'HTTP_API' => 'Merchant API',
        'DATABASE' => 'Managed',
        _ => 'Inline',
      };

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final managed = catalog.locationKind == 'DATABASE';
    return EditableRecordCard(
      leading: catalog.imageUrl == null
          ? const Icon(Icons.shopping_bag_outlined)
          : ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: Image.network(catalog.imageUrl!, width: 36, height: 36, fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => const Icon(Icons.shopping_bag_outlined))),
      header: Text('${catalog.id}@${catalog.version}  ·  ${catalog.displayName}',
          style: const TextStyle(fontWeight: FontWeight.w600)),
      onCloneEdit: onCloneEdit,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'currency', value: catalog.currency),
          DetailRow(label: 'source', value: _source),
          DetailRow(
              label: 'products',
              value: catalog.productCount < 0 ? '(external)' : '${catalog.productCount}'),
          if (catalog.imageUrl != null)
            Text(catalog.imageUrl!, style: TextStyle(fontSize: 11, color: scheme.outline)),
          const SizedBox(height: 8),
          Row(children: [
            if (managed)
              FilledButton.tonalIcon(
                onPressed: onManageProducts,
                icon: const Icon(Icons.inventory_2_outlined, size: 18),
                label: const Text('Manage products'),
              )
            else if (catalog.locationKind == 'INLINE')
              OutlinedButton.icon(
                onPressed: onConvert,
                icon: const Icon(Icons.upgrade, size: 18),
                label: const Text('Convert to managed'),
              ),
          ]),
        ],
      ),
    );
  }
}
