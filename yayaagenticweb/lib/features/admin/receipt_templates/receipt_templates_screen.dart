import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/receipt_template.dart';
import '../shared/admin_shared.dart';
import 'receipt_preview.dart';

final receiptTemplatesProvider =
    FutureProvider.family<List<ReceiptTemplateResponse>, String>(
  (ref, tenant) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listReceiptTemplates(tenant);
  },
);

/// Receipt template authoring (chat-experience-design.md §7.4): tenant-scoped
/// CRUD over the closed layout vocabulary plus a live preview that renders the
/// template against built-in sample data — the console renders the same
/// contract the chat app does, so what you see here is what the receipt
/// bubble shows.
class ReceiptTemplatesScreen extends ConsumerStatefulWidget {
  const ReceiptTemplatesScreen({super.key});

  @override
  ConsumerState<ReceiptTemplatesScreen> createState() =>
      _ReceiptTemplatesScreenState();
}

class _ReceiptTemplatesScreenState
    extends ConsumerState<ReceiptTemplatesScreen> {
  final _nameCtrl = TextEditingController();
  final _kindLabelCtrl = TextEditingController();
  final _paperColsCtrl = TextEditingController(text: '32');
  final _elementsCtrl = TextEditingController();
  String _paperProfile = '58mm';
  String? _editingId;
  Map<String, dynamic>? _brand; // preserved verbatim across edit round-trips
  bool _saving = false;

  /// Inline JSON parse error for the elements editor; null when valid.
  String? _jsonError;

  /// Last successfully parsed elements array — the preview keeps rendering
  /// this while the operator types through an invalid intermediate state.
  List<dynamic> _parsedElements = const [];

  static const _prettyJson = JsonEncoder.withIndent('  ');

  @override
  void initState() {
    super.initState();
    _elementsCtrl.addListener(_reparse);
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _kindLabelCtrl.dispose();
    _paperColsCtrl.dispose();
    _elementsCtrl.dispose();
    super.dispose();
  }

  void _reparse() {
    final text = _elementsCtrl.text.trim();
    if (text.isEmpty) {
      setState(() {
        _jsonError = null;
        _parsedElements = const [];
      });
      return;
    }
    try {
      final decoded = jsonDecode(text);
      if (decoded is! List) {
        setState(() =>
            _jsonError = 'Top level must be a JSON array of elements.');
        return;
      }
      setState(() {
        _jsonError = null;
        _parsedElements = decoded;
      });
    } on FormatException catch (e) {
      setState(() => _jsonError = e.message);
    }
  }

  void _resetForm() {
    setState(() {
      _editingId = null;
      _brand = null;
      _nameCtrl.clear();
      _kindLabelCtrl.clear();
      _paperColsCtrl.text = '32';
      _paperProfile = '58mm';
      _elementsCtrl.clear();
    });
  }

  void _loadIntoForm(ReceiptTemplateResponse row) {
    setState(() {
      _editingId = row.id;
      _brand = row.brand;
      _nameCtrl.text = row.name;
      _kindLabelCtrl.text = row.kindLabel ?? '';
      _paperColsCtrl.text = '${row.paperCols}';
      _paperProfile = row.paperProfile == '80mm' ? '80mm' : '58mm';
      _elementsCtrl.text = _prettyJson.convert(row.elements);
    });
  }

  void _loadStarter({required List<Map<String, dynamic>> elements,
      required int cols, required String profile}) {
    setState(() {
      _paperColsCtrl.text = '$cols';
      _paperProfile = profile;
      _elementsCtrl.text = _prettyJson.convert(elements);
    });
  }

  String? _validate() {
    if (_nameCtrl.text.trim().isEmpty) return 'Template name is required.';
    final cols = int.tryParse(_paperColsCtrl.text.trim());
    if (cols == null || cols < 20 || cols > 64) {
      return 'Paper columns must be a number between 20 and 64.';
    }
    if (_jsonError != null) return 'Fix the elements JSON first: $_jsonError';
    if (_parsedElements.isEmpty) return 'Elements must be a non-empty JSON array.';
    return null;
  }

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final err = _validate();
    if (err != null) {
      showSnack(context, err, error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = ReceiptTemplateRequest(
        name: _nameCtrl.text.trim(),
        kindLabel: _kindLabelCtrl.text.trim().isEmpty
            ? null
            : _kindLabelCtrl.text.trim(),
        paperCols: int.parse(_paperColsCtrl.text.trim()),
        paperProfile: _paperProfile,
        elements: _parsedElements,
        brand: _brand,
      );
      if (_editingId == null) {
        await api.createReceiptTemplate(tenant, body);
      } else {
        await api.updateReceiptTemplate(tenant, _editingId!, body);
      }
      ref.invalidate(receiptTemplatesProvider(tenant));
      if (!mounted) return;
      _resetForm();
      showSnack(context, 'Template saved');
    } catch (e) {
      // Server-side vocabulary validation lands here as a 400 whose message
      // names the offending element — surface it verbatim.
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(ReceiptTemplateResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Delete this template?'),
            content: Text(
                '"${row.name}" will be removed. Tools bound to it will fall '
                'back to unstyled rendering until rebound.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Delete')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteReceiptTemplate(tenant, row.id);
      ref.invalidate(receiptTemplatesProvider(tenant));
      if (_editingId == row.id) _resetForm();
      if (mounted) showSnack(context, 'Template deleted');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Receipt templates');
    }
    final templates = ref.watch(receiptTemplatesProvider(tenant));

    return AdminTwoPane(
      title: 'Receipt templates',
      subtitle:
          'Thermal-receipt layouts the chat app renders when a tool emits a '
          'receipt hint. Elements bind {{data}} paths at runtime — labels '
          'included — so the layout stays domain-free.',
      list: templates.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) {
          if (list.isEmpty) {
            return const Center(
                child: Text(
                    'No templates yet. Load a starter on the right to begin.'));
          }
          return ListView.separated(
            itemBuilder: (_, i) => _TemplateTile(
              row: list[i],
              onEdit: () => _loadIntoForm(list[i]),
              onDelete: () => _delete(list[i]),
            ),
            separatorBuilder: (_, i) => const SizedBox(height: 8),
            itemCount: list.length,
          );
        },
      ),
      form: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FormCard(
              title: _editingId == null
                  ? 'New template'
                  : 'Editing "${_nameCtrl.text}"',
              actions: [
                if (_editingId != null)
                  TextButton(
                    onPressed: _saving ? null : _resetForm,
                    child: const Text('Cancel'),
                  ),
                FilledButton(
                  onPressed: _saving ? null : _save,
                  child: _saving
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_editingId == null ? 'Create' : 'Save'),
                ),
              ],
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextField(
                    controller: _nameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Name',
                      helperText:
                          'Operator-facing template name (e.g. "Quiniela ticket").',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _kindLabelCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Kind label (optional)',
                      helperText:
                          'Short badge shown on the receipt bubble (e.g. "Ticket").',
                    ),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _paperColsCtrl,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(
                            labelText: 'Paper columns',
                            helperText: '20–64 (32 fits 58 mm).',
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: DropdownButtonFormField<String>(
                          initialValue: _paperProfile,
                          isExpanded: true,
                          decoration:
                              const InputDecoration(labelText: 'Paper profile'),
                          items: const [
                            DropdownMenuItem(
                                value: '58mm', child: Text('58mm')),
                            DropdownMenuItem(
                                value: '80mm', child: Text('80mm')),
                          ],
                          onChanged: (v) =>
                              setState(() => _paperProfile = v ?? '58mm'),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      OutlinedButton.icon(
                        onPressed: () => _loadStarter(
                            elements: _genericSaleStarter,
                            cols: 32,
                            profile: '58mm'),
                        icon: const Icon(Icons.receipt_outlined, size: 18),
                        label: const Text('Generic sale receipt'),
                      ),
                      OutlinedButton.icon(
                        onPressed: () => _loadStarter(
                            elements: _numberedTicketStarter,
                            cols: 32,
                            profile: '58mm'),
                        icon: const Icon(
                            Icons.confirmation_number_outlined, size: 18),
                        label: const Text('Numbered ticket with picks grid'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _elementsCtrl,
                    maxLines: 18,
                    minLines: 10,
                    style: const TextStyle(
                        fontFamily: 'monospace', fontSize: 12),
                    decoration: InputDecoration(
                      labelText: 'Elements (JSON array)',
                      alignLabelWithHint: true,
                      helperText:
                          'Vocabulary: header, status, meta, divider, title, '
                          'text, kv, section, table, cells, total, code, '
                          'barcode, qr, footer, spacer. Bind with {{path}}; '
                          'formatters: money, maskTail:n, group:n, chunk:n.',
                      helperMaxLines: 3,
                      errorText: _jsonError,
                      errorMaxLines: 3,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text('Live preview — sample data',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 4),
                    Text(
                      'Rendered with built-in sample data; at runtime the '
                      'emitting tool supplies the data (and labels) per session.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.outline),
                    ),
                    const SizedBox(height: 12),
                    if (_parsedElements.isEmpty)
                      Padding(
                        padding: const EdgeInsets.all(24),
                        child: Center(
                          child: Text(
                            'Add elements (or load a starter) to see the preview.',
                            style: TextStyle(
                                color: Theme.of(context).colorScheme.outline),
                          ),
                        ),
                      )
                    else
                      ReceiptPreview(
                        elements: _parsedElements,
                        paperCols:
                            (int.tryParse(_paperColsCtrl.text.trim()) ?? 32)
                                .clamp(20, 64),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TemplateTile extends StatelessWidget {
  const _TemplateTile({
    required this.row,
    required this.onEdit,
    required this.onDelete,
  });

  final ReceiptTemplateResponse row;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return EditableRecordCard(
      onCloneEdit: onEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(row.name, style: Theme.of(context).textTheme.titleSmall),
          Text(
            [
              if (row.kindLabel != null && row.kindLabel!.isNotEmpty)
                row.kindLabel!,
              '${row.paperCols} cols · ${row.paperProfile}',
              if (row.updatedAt != null) 'updated ${row.updatedAt}',
            ].join(' · '),
            style: const TextStyle(fontSize: 12),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Id', value: row.id),
          DetailRow(
              label: 'Elements',
              value:
                  '${row.elements.length} element${row.elements.length == 1 ? '' : 's'}'),
          if (row.createdAt != null)
            DetailRow(label: 'Created', value: row.createdAt!),
          if (row.updatedAt != null)
            DetailRow(label: 'Updated', value: row.updatedAt!),
        ],
      ),
      extraActions: [
        TextButton.icon(
          onPressed: onDelete,
          icon: const Icon(Icons.delete_outline, size: 18),
          label: const Text('Delete'),
          style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error),
        ),
      ],
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
