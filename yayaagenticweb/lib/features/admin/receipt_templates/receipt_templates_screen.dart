import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/receipt_template.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';
import 'receipt_preview.dart';

final receiptTemplatesProvider = FutureProvider.family<List<ReceiptTemplateResponse>, String>(
  (ref, tenant) async {
    liveResource(ref, AdminResourceKind.receiptTemplate);
    final api = await ref.watch(adminApiProvider.future);
    return api.listReceiptTemplates(tenant);
  },
);

const _routePath = '/admin/receipt-templates';

/// How the receipt template screen open a side sheet. Tests swap in a plain route:
/// the kit's sheet frame (`Semantics(scopesRoute: true)` without
/// `explicitChildNodes`) fails a debug assertion when it mounts.
Future<R?> Function<R>(BuildContext context, {required WidgetBuilder builder, double width, String label})
    openReceiptSheet = showYSheet;


Map<String, String> _routeParams(BuildContext context) =>
    GoRouter.maybeOf(context) == null ? const {} : GoRouterState.of(context).uri.queryParameters;

/// The closed v1 layout vocabulary the backend accepts (chat-experience-design.md §7.4).
const kReceiptElementTypes = [
  'header', 'status', 'meta', 'divider', 'title', 'text', 'kv', 'section',
  'table', 'cells', 'total', 'code', 'barcode', 'qr', 'footer', 'spacer',
];

const _maxElements = 80;

/// The first rule [elements] breaks, as the backend checks it; null when fine.
String? receiptElementsProblem(Object? elements) {
  if (elements is! List) return 'The top level must be a JSON array of elements: [ { "type": … }, … ].';
  if (elements.isEmpty) return 'Add at least one element, or start from a starter layout.';
  if (elements.length > _maxElements) return 'Use at most $_maxElements elements (it has ${elements.length}).';
  String? check(Object? el, String where, {required bool allowSection}) {
    if (el is! Map) return '$where must be an object with a "type".';
    final type = el['type'];
    if (type is! String || !kReceiptElementTypes.contains(type)) {
      return '$where has type ${type == null ? 'missing' : '"$type"'} — use one of: ${kReceiptElementTypes.join(', ')}.';
    }
    if (type == 'section') {
      if (!allowSection) return '$where is a section inside a section; sections can’t nest.';
      final inner = el['elements'];
      if (inner is! List || inner.isEmpty) return '$where is a section without child "elements".';
      for (var j = 0; j < inner.length; j++) {
        final err = check(inner[j], '$where, child ${j + 1},', allowSection: false);
        if (err != null) return err;
      }
    }
    return null;
  }

  for (var i = 0; i < elements.length; i++) {
    final err = check(elements[i], 'Element ${i + 1}', allowSection: true);
    if (err != null) return err;
  }
  return null;
}

/// Receipt template authoring (chat-experience-design.md §7.4): tenant-scoped
/// CRUD over the closed layout vocabulary plus a live preview that renders the
/// template against built-in sample data — the console renders the same
/// contract the chat app does, so what you see here is what the receipt
/// bubble shows.
class ReceiptTemplatesScreen extends ConsumerStatefulWidget {
  const ReceiptTemplatesScreen({super.key});

  @override
  ConsumerState<ReceiptTemplatesScreen> createState() => _ReceiptTemplatesScreenState();
}

class _ReceiptTemplatesScreenState extends ConsumerState<ReceiptTemplatesScreen> {
  String? _handledLink;

  Future<void> _open({ReceiptTemplateResponse? existing, ReceiptTemplateResponse? copyOf}) async {
    await openReceiptSheet<void>(
      context,
      width: 1040,
      label: existing == null ? 'New receipt template' : 'Edit receipt template',
      builder: (_) => ReceiptTemplateEditorSheet(
        existing: existing,
        copyOf: copyOf,
        onDuplicate: (t) => WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _open(copyOf: t);
        }),
      ),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the editor once the list is in.
  void _handleDeepLink(List<ReceiptTemplateResponse> list) {
    final params = _routeParams(context);
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((t) => t.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No receipt template “$link” on this tenant', error: true);
      } else {
        await _open(existing: match);
      }
      if (mounted) GoRouter.maybeOf(context)?.go(_routePath);
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Receipt templates');
    }
    final templates = ref.watch(receiptTemplatesProvider(tenant));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.receiptTemplate));
    templates.whenData(_handleDeepLink);

    return YPage(
      title: 'Receipt templates',
      subtitle: 'Thermal-receipt layouts the chat app renders when a tool emits a receipt hint. Elements bind '
          '{{data}} paths at runtime — labels included — so the layout stays domain-free.',
      actions: [
        FilledButton.icon(onPressed: () => _open(), icon: const Icon(Icons.add, size: 18), label: const Text('New template')),
      ],
      child: LiveAsyncView<List<ReceiptTemplateResponse>>(
        value: templates,
        what: 'receipt templates',
        onRetry: () => ref.invalidate(receiptTemplatesProvider(tenant)),
        data: (context, list) => YTable<ReceiptTemplateResponse>(
          rows: list,
          noun: 'templates',
          idOf: (t) => t.id,
          searchHint: 'Search by name, badge or ID',
          searchTextOf: (t) => '${t.name} ${t.kindLabel ?? ''} ${t.id} ${t.paperProfile}',
          highlightIds: fresh,
          onRowTap: (t) => _open(existing: t),
          filters: [
            YFilter('58 mm', (t) => t.paperProfile == '58mm'),
            YFilter('80 mm', (t) => t.paperProfile == '80mm'),
          ],
          empty: YEmptyState(
            icon: Icons.receipt_long_outlined,
            title: 'No receipt templates yet',
            message: 'A receipt template lays out the ticket or sale receipt the chat shows after a tool completes a purchase.',
            action: FilledButton.icon(
              onPressed: () => _open(),
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Create your first template'),
            ),
          ),
          columns: [
            YColumn(
              label: 'Template',
              flex: 4,
              sortKey: (t) => t.name.toLowerCase(),
              cell: (context, t) => YCellStack(primary: t.name, secondary: t.id),
            ),
            YColumn(
              label: 'Badge',
              flex: 2,
              sortKey: (t) => t.kindLabel ?? '',
              cell: (context, t) => (t.kindLabel ?? '').isEmpty
                  ? Text('None', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
                  : YBadge(t.kindLabel!),
            ),
            YColumn(
              label: 'Paper',
              width: 150,
              sortKey: (t) => t.paperCols,
              cell: (context, t) => YCellStack(primary: t.paperProfile, mono: true, secondary: '${t.paperCols} columns'),
            ),
            YColumn(
              label: 'Elements',
              width: 130,
              sortKey: (t) => t.elements.length,
              cell: (context, t) => Text('${t.elements.length}', style: monoStyle(context)),
            ),
            YColumn(
              label: 'Updated',
              width: 130,
              sortKey: (t) => t.updatedAt ?? t.createdAt ?? '',
              cell: (context, t) => Text(fresh.contains(t.id) ? 'Just now' : formatRelativeIso(t.updatedAt ?? t.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

const _labels = {
  'name': 'Name',
  'kindLabel': 'Badge',
  'paperCols': 'Paper columns',
  'paperProfile': 'Paper width',
  'elements': 'Layout elements',
};

const _prettyJson = JsonEncoder.withIndent('  ');

/// Create or edit one receipt template, with the live preview beside it.
class ReceiptTemplateEditorSheet extends ConsumerStatefulWidget {
  const ReceiptTemplateEditorSheet({super.key, this.existing, this.copyOf, this.onDuplicate});
  final ReceiptTemplateResponse? existing;

  /// Prefills a new template from this one.
  final ReceiptTemplateResponse? copyOf;
  final ValueChanged<ReceiptTemplateResponse>? onDuplicate;

  @override
  ConsumerState<ReceiptTemplateEditorSheet> createState() => _ReceiptTemplateEditorSheetState();
}

class _ReceiptTemplateEditorSheetState extends ConsumerState<ReceiptTemplateEditorSheet> {
  final _name = TextEditingController();
  final _kindLabel = TextEditingController();
  final _paperCols = TextEditingController(text: '32');
  final _elements = TextEditingController();
  String _paperProfile = '58mm';
  late final YFormController _form;

  /// Preserved verbatim: the console doesn't edit the brand block.
  Map<String, dynamic>? _brand;

  /// The last elements that parsed — the preview keeps rendering them while
  /// the operator types through an invalid intermediate state.
  List<dynamic> _parsed = const [];

  ReceiptTemplateResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    final src = widget.existing ?? widget.copyOf;
    _name.text = widget.copyOf != null ? '${src!.name} (copy)' : (src?.name ?? '');
    _kindLabel.text = src?.kindLabel ?? '';
    _paperCols.text = '${src?.paperCols ?? 32}';
    _paperProfile = src?.paperProfile == '80mm' ? '80mm' : '58mm';
    _elements.text = src == null || src.elements.isEmpty ? '' : _prettyJson.convert(src.elements);
    _brand = src?.brand;
    _reparse();
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'name': _name.text.trim(),
        'kindLabel': _kindLabel.text.trim(),
        'paperCols': _paperCols.text.trim(),
        'paperProfile': _paperProfile,
        'elements': _elements.text,
      },
      validate: _validate,
      labels: _labels,
      draftKey: widget.copyOf == null ? 'yaya.draft.receiptTemplate.$tenant.${_base?.id ?? 'new'}' : null,
    );
    _form.addListener(_rebuild);
    _elements.addListener(_onElements);
    _paperCols.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _name.text = (v['name'] as String?) ?? _name.text;
      _kindLabel.text = (v['kindLabel'] as String?) ?? _kindLabel.text;
      _paperCols.text = (v['paperCols'] as String?) ?? _paperCols.text;
      if (v['paperProfile'] == '58mm' || v['paperProfile'] == '80mm') _paperProfile = v['paperProfile'] as String;
      _elements.text = (v['elements'] as String?) ?? _elements.text;
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _onElements() {
    _reparse();
    _rebuild();
  }

  /// The elements text decoded, or the parse error.
  (Object?, String?) _decode() {
    final text = _elements.text.trim();
    if (text.isEmpty) return (null, null);
    try {
      return (jsonDecode(text), null);
    } on FormatException catch (e) {
      return (null, 'Not valid JSON — ${e.message}${e.offset != null ? ' (at character ${e.offset})' : ''}.');
    }
  }

  void _reparse() {
    final (value, err) = _decode();
    if (err == null && value is List) _parsed = value;
    if (_elements.text.trim().isEmpty) _parsed = const [];
  }

  Map<String, String?> _validate() {
    final (value, parseErr) = _decode();
    final cols = _paperCols.text.trim();
    return {
      'name': Validators.required(_name.text, 'a name'),
      'paperCols': cols.isEmpty ? 'Enter the paper width in characters.' : Validators.intInRange(cols, 20, 64),
      'elements': _elements.text.trim().isEmpty
          ? 'Add the layout elements, or start from a starter layout.'
          : parseErr ?? receiptElementsProblem(value),
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _elements.removeListener(_onElements);
    _paperCols.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_name, _kindLabel, _paperCols, _elements]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _loadStarter(String label, List<Map<String, dynamic>> elements) async {
    if (_elements.text.trim().isNotEmpty) {
      final ok = await confirmAction(
        context,
        title: 'Replace the layout with “$label”?',
        message: 'The elements you have now are replaced. Name, badge and paper stay as they are.',
        confirmLabel: 'Replace layout',
      );
      if (!ok) return;
    }
    _elements.text = _prettyJson.convert(elements);
    _paperCols.text = '32';
    _paperProfile = '58mm';
    _form.changed('elements');
  }

  void _format() {
    final (value, err) = _decode();
    if (err != null || value == null) {
      _form.touch('elements');
      return;
    }
    _elements.text = _prettyJson.convert(value);
    _form.changed('elements');
  }

  /// Server-side vocabulary errors arrive as a 400 whose message names the
  /// offending part; put it under that field.
  ApiErrorInfo _fieldError(Object e) {
    final info = ApiErrorInfo.from(e);
    if (info.fieldErrors.isNotEmpty || info.status != 400) return info;
    final m = info.message.toLowerCase();
    final field = m.startsWith('name')
        ? 'name'
        : m.startsWith('papercols')
            ? 'paperCols'
            : m.startsWith('paperprofile')
                ? 'paperProfile'
                : (m.contains('element') || m.contains('section'))
                    ? 'elements'
                    : null;
    if (field == null) return info;
    final text = info.message.isEmpty ? info.message : '${info.message[0].toUpperCase()}${info.message.substring(1)}.';
    return ApiErrorInfo(message: info.message, code: info.code, status: info.status, path: info.path, fieldErrors: {field: text});
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final (value, _) = _decode();
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = ReceiptTemplateRequest(
        name: _name.text.trim(),
        kindLabel: _kindLabel.text.trim().isEmpty ? null : _kindLabel.text.trim(),
        paperCols: int.parse(_paperCols.text.trim()),
        paperProfile: _paperProfile,
        elements: value as List,
        brand: _brand,
      );
      final saved = _editing
          ? await api.updateReceiptTemplate(tenant, _base!.id, body)
          : await api.createReceiptTemplate(tenant, body);
      ref.invalidate(receiptTemplatesProvider(tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _editing ? 'Saved ${saved.name}' : 'Created ${saved.name}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(_fieldError(e));
    }
  }

  Future<void> _delete() async {
    final base = _base;
    final tenant = ref.read(currentTenantOrNull);
    if (base == null || tenant == null) return;
    final ok = await confirmAction(
      context,
      destructive: true,
      title: 'Delete ${base.name}?',
      message: 'Tools bound to this template fall back to unstyled rendering until they are bound to another one. '
          'This can’t be undone.',
      confirmLabel: 'Delete template',
    );
    if (!ok || !mounted) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteReceiptTemplate(tenant, base.id);
      ref.invalidate(receiptTemplatesProvider(tenant));
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Deleted ${base.name}');
      Navigator.of(context).pop();
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    }
  }

  Future<void> _duplicate() async {
    final base = _base;
    if (base == null) return;
    if (!await UnsavedChanges.instance.confirmLeave(context)) return;
    _form.markClean();
    if (!mounted) return;
    Navigator.of(context).pop();
    widget.onDuplicate?.call(base);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final (value, parseErr) = _decode();
    final problem = _elements.text.trim().isEmpty ? null : parseErr ?? receiptElementsProblem(value);
    final cols = (int.tryParse(_paperCols.text.trim()) ?? 32).clamp(20, 64);
    final n = value is List ? value.length : _parsed.length;

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? _base!.name : 'New receipt template',
          subtitle: _editing
              ? '${_base!.id} · saving replaces the layout for every tool bound to it.'
              : 'Fields marked * are required. Start from a starter layout and adjust it.',
          headerActions: [
            if (_editing) ...[
              TextButton.icon(onPressed: _duplicate, icon: const Icon(Icons.copy_outlined, size: 18), label: const Text('Duplicate')),
              TextButton.icon(
                onPressed: _delete,
                style: TextButton.styleFrom(foregroundColor: scheme.error),
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('Delete'),
              ),
              const SizedBox(width: 4),
            ],
          ],
          body: LayoutBuilder(builder: (context, c) {
            final form = <Widget>[
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this template'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Name',
                wireKey: 'name',
                controller: _name,
                form: _form,
                name: 'name',
                required: true,
                autofocus: !_editing,
                hint: 'e.g. Quiniela ticket',
                helper: 'What operators see when binding a tool to this layout.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Badge',
                wireKey: 'kindLabel',
                controller: _kindLabel,
                form: _form,
                name: 'kindLabel',
                optional: true,
                hint: 'e.g. Ticket',
                helper: 'A short label on the receipt bubble in the chat.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<String>(
                label: 'Paper width',
                wireKey: 'paperProfile',
                form: _form,
                name: 'paperProfile',
                required: true,
                horizontal: true,
                value: _paperProfile,
                options: const [
                  YEnumOption('58mm', '58 mm roll', wire: '58mm', description: 'Pocket printers; about 32 characters a line.'),
                  YEnumOption('80mm', '80 mm roll', wire: '80mm', description: 'Counter printers; about 48 characters a line.'),
                ],
                onChanged: (v) {
                  setState(() {
                    // Keep the columns in step with the roll unless they were set by hand.
                    if (_paperCols.text.trim() == (_paperProfile == '58mm' ? '32' : '48')) {
                      _paperCols.text = v == '58mm' ? '32' : '48';
                    }
                    _paperProfile = v;
                  });
                  _form.refresh();
                },
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Paper columns',
                wireKey: 'paperCols',
                controller: _paperCols,
                form: _form,
                name: 'paperCols',
                required: true,
                mono: true,
                keyboardType: TextInputType.number,
                hint: 'e.g. 32',
                helper: 'Characters per line, 20–64. 32 fits a 58 mm roll, 48 an 80 mm one.',
              ),
              const SizedBox(height: Gap.xl),
              YFieldShell(
                label: 'Layout elements',
                wireKey: 'elements',
                form: _form,
                name: 'elements',
                required: true,
                helper: 'A JSON array, top to bottom. Types: ${kReceiptElementTypes.join(', ')}. Bind data with '
                    '{{path}}; formatters: money, maskTail:n, group:n, chunk:n. Sections nest one level.',
                labelTrailing: Wrap(spacing: 4, children: [
                  TextButton.icon(
                    onPressed: () => _loadStarter('Generic sale receipt', _genericSaleStarter),
                    icon: const Icon(Icons.receipt_outlined, size: 18),
                    label: const Text('Sale receipt starter'),
                  ),
                  TextButton.icon(
                    onPressed: () => _loadStarter('Numbered ticket', _numberedTicketStarter),
                    icon: const Icon(Icons.confirmation_number_outlined, size: 18),
                    label: const Text('Numbered ticket starter'),
                  ),
                ]),
                child: Container(
                  decoration: BoxDecoration(
                    color: scheme.surface,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(
                      color: _form.errorFor('elements') != null ? scheme.error : status.inputBorder,
                      width: _form.errorFor('elements') != null ? 2 : 1,
                    ),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Container(
                      color: scheme.surfaceContainerLow,
                      padding: const EdgeInsets.fromLTRB(12, 2, 4, 2),
                      child: Row(children: [
                        Icon(problem == null ? Icons.check_circle_outline : Icons.error_outline,
                            size: 16, color: problem == null ? status.onSuccessContainer : scheme.error),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            _elements.text.trim().isEmpty
                                ? 'Empty'
                                : problem ?? 'Valid layout · $n element${n == 1 ? '' : 's'}',
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                                fontSize: 13,
                                fontWeight: FontWeight.w500,
                                color: problem == null ? status.onSuccessContainer : scheme.error),
                          ),
                        ),
                        TextButton(onPressed: _format, child: const Text('Format')),
                      ]),
                    ),
                    Divider(height: 1, color: scheme.outlineVariant),
                    TextField(
                      key: const ValueKey('receipt-elements'),
                      controller: _elements,
                      minLines: 14,
                      maxLines: 28,
                      keyboardType: TextInputType.multiline,
                      style: monoStyle(context, size: 13),
                      onChanged: (_) => _form.changed('elements'),
                      decoration: const InputDecoration(
                        border: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        filled: false,
                        contentPadding: EdgeInsets.all(12),
                      ),
                    ),
                  ]),
                ),
              ),
              if (_brand != null) ...[
                const SizedBox(height: Gap.lg),
                Text('This template carries brand settings set through the API; saving keeps them as they are.',
                    style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant)),
              ],
            ];
            final preview = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('Live preview', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
              const SizedBox(height: 4),
              Text(
                'Rendered with built-in sample data; at runtime the tool that emits the receipt supplies the data '
                '(and labels) per session.',
                style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
              ),
              if (problem != null && _parsed.isNotEmpty) ...[
                const SizedBox(height: Gap.md),
                const YBanner(tone: YBannerTone.warning, message: 'Showing the last layout that parsed.'),
              ],
              const SizedBox(height: Gap.md),
              if (_parsed.isEmpty)
                Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(color: scheme.surfaceContainerHigh, borderRadius: BorderRadius.circular(8)),
                  child: Text('Add elements, or start from a starter layout, to see the receipt.',
                      textAlign: TextAlign.center, style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant)),
                )
              else
                FittedBox(fit: BoxFit.scaleDown, child: ReceiptPreview(elements: _parsed, paperCols: cols)),
            ]);
            if (c.maxWidth < 900) {
              return ListView(
                padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
                children: [...form, const SizedBox(height: Gap.xl), preview],
              );
            }
            return Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Expanded(child: ListView(padding: const EdgeInsets.fromLTRB(28, 20, 24, 28), children: form)),
              Container(
                width: 460,
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerLowest,
                  border: Border(left: BorderSide(color: scheme.outlineVariant)),
                ),
                child: ListView(padding: const EdgeInsets.fromLTRB(20, 20, 20, 28), children: [preview]),
              ),
            ]);
          }),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save template' : 'Create template',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Starter templates (§7.7 "starter gallery" — names live only in console
// copy). Every domain word below is a {{data}} binding, never schema.
// ---------------------------------------------------------------------------

const List<Map<String, dynamic>> _genericSaleStarter = [
  {'type': 'header', 'logo': true, 'title': '{{store.name}}', 'subtitle': '{{store.legal}}'},
  {'type': 'meta', 'left': '{{issuedAt}}', 'right': 'N° {{serial}}'},
  {'type': 'divider', 'style': 'dashed'},
  {'type': 'title', 'text': '{{docTitle}}', 'align': 'center'},
  {'type': 'divider', 'style': 'dashed'},
  {
    'type': 'table',
    'repeat': 'lines',
    'span': 'full',
    'cols': [
      {'bind': 'label', 'align': 'left'},
      {'bind': 'qty', 'align': 'center'},
      {'bind': 'amount', 'align': 'right', 'format': 'money'},
    ],
  },
  {'type': 'divider', 'style': 'dashed'},
  {'type': 'total', 'label': '{{totalLabel}}', 'value': '{{totalAmount | money}}'},
  {'type': 'spacer', 'size': 8},
  {'type': 'barcode', 'symbology': 'code128', 'value': '{{serial}}'},
  {'type': 'footer', 'lines': ['{{disclaimer1}}', '{{disclaimer2}}']},
];

// Faithful replica of a real counter-printed ticket (the shipped thermal
// `detailed` layout): header -> meta -> draw sections -> Count|Total ->
// payment split -> grouped-by-two payment code -> customer -> QR -> hash ->
// footer + disclaimer -> printed-at. Kept in sync with the backend seed
// (ReceiptTemplateDefaults.numberedTicket) — every domain word is data.
const List<Map<String, dynamic>> _numberedTicketStarter = [
  {'type': 'header', 'logo': true, 'title': '{{org.name}}', 'subtitle': '{{station.name}}'},
  {'type': 'status', 'text': '{{statusStamp}}'},
  {'type': 'kv', 'label': '{{labels.date}}', 'value': '{{issuedAt}}'},
  {'type': 'kv', 'label': '{{labels.ticket}}', 'value': '{{ticketRef}}'},
  {'type': 'divider', 'style': 'dashed'},
  {
    'type': 'section',
    'repeat': 'sections',
    'title': '{{title}}',
    'subtitle': '{{forDraws}}',
    'elements': [
      {
        'type': 'table',
        'repeat': 'rows',
        'cols': [
          {'bind': 'play', 'align': 'left'},
          {'bind': 'amount', 'align': 'right', 'format': 'money'},
        ],
      },
    ],
  },
  {'type': 'divider', 'style': 'dashed'},
  {'type': 'meta', 'left': '{{labels.count}}: {{count}}', 'right': '{{labels.total}}: {{total | money}}'},
  {'type': 'kv', 'label': '{{labels.points}}', 'value': '{{payment.points | money}}'},
  {'type': 'kv', 'label': '{{labels.balance}}', 'value': '{{payment.wallet | money}}'},
  {'type': 'kv', 'label': '{{labels.cashDue}}', 'value': '{{payment.cashDue | money}}'},
  {'type': 'code', 'label': '{{labels.paymentCode}}', 'value': '{{paymentCode | group:2}}'},
  {'type': 'kv', 'label': '{{labels.customer}}', 'value': '{{customer.name}} {{customer.phone | maskTail:4}}'},
  {'type': 'qr', 'value': '{{verifyUrl}}'},
  {'type': 'text', 'text': '{{ticketHash}}', 'align': 'center'},
  {
    'type': 'footer',
    'lines': ['{{disclaimer}}', '{{labels.checkTicket}}', '{{labels.thanks}}'],
  },
  {'type': 'text', 'text': '{{labels.printed}}: {{printedAt}}', 'align': 'center'},
];
