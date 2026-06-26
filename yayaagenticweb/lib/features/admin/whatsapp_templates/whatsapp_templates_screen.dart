import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_template.dart';
import '../shared/admin_shared.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart' show whatsAppSendersProvider;

final whatsAppTemplatesProvider = FutureProvider.family<
    List<WhatsAppTemplateResponse>, ({String tenant, String sender})>(
  (ref, key) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listWhatsAppTemplates(key.tenant, key.sender);
  },
);

class WhatsAppTemplatesScreen extends ConsumerStatefulWidget {
  const WhatsAppTemplatesScreen({super.key});

  @override
  ConsumerState<WhatsAppTemplatesScreen> createState() =>
      _WhatsAppTemplatesScreenState();
}

class _WhatsAppTemplatesScreenState
    extends ConsumerState<WhatsAppTemplatesScreen> {
  final _nameCtrl = TextEditingController();
  final _localeCtrl = TextEditingController(text: 'en-US');
  final _bodyCtrl = TextEditingController();
  String _category = 'UTILITY';
  String? _senderId;
  int? _editingId;
  bool _saving = false;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _localeCtrl.dispose();
    _bodyCtrl.dispose();
    super.dispose();
  }

  void _resetForm() {
    setState(() {
      _editingId = null;
      _nameCtrl.clear();
      _bodyCtrl.clear();
      _localeCtrl.text = 'en-US';
      _category = 'UTILITY';
    });
  }

  void _loadIntoForm(WhatsAppTemplateResponse row) {
    setState(() {
      _editingId = row.id;
      _nameCtrl.text = row.name;
      _localeCtrl.text = row.locale;
      _bodyCtrl.text = row.body;
      _category = row.category;
    });
  }

  String? _validate() {
    if (_senderId == null) return 'Pick a sender first.';
    if (_nameCtrl.text.trim().isEmpty) return 'Template name is required.';
    if (_localeCtrl.text.trim().isEmpty) return 'Locale is required.';
    if (_bodyCtrl.text.trim().isEmpty) return 'Template body is required.';
    if (_bodyCtrl.text.length > 1024) {
      return 'Template body must be 1024 characters or fewer.';
    }
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
      if (_editingId == null) {
        await api.createWhatsAppTemplate(
            tenant,
            _senderId!,
            WhatsAppTemplateRequest(
              name: _nameCtrl.text.trim(),
              locale: _localeCtrl.text.trim(),
              category: _category,
              body: _bodyCtrl.text,
            ));
      } else {
        await api.updateWhatsAppTemplate(
            tenant,
            _senderId!,
            _editingId!,
            WhatsAppTemplateUpdate(
              body: _bodyCtrl.text,
              category: _category,
            ));
      }
      ref.invalidate(whatsAppTemplatesProvider(
          (tenant: tenant, sender: _senderId!)));
      if (!mounted) return;
      _resetForm();
      showSnack(context, 'Template saved');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _submit(WhatsAppTemplateResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Submit template for review?'),
            content: const Text(
                'WhatsApp reviews each template before it can be used to '
                'message customers. This typically takes 1–3 business days. '
                'You cannot edit the template while it is under review.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Submit')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.submitWhatsAppTemplate(tenant, _senderId!, row.id);
      ref.invalidate(
          whatsAppTemplatesProvider((tenant: tenant, sender: _senderId!)));
      if (mounted) showSnack(context, 'Template submitted');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _delete(WhatsAppTemplateResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove this template?'),
            content: Text(row.status == 'APPROVED'
                ? 'Approved templates cannot be deleted — they will be paused '
                    'instead, keeping the audit trail.'
                : '${row.name} (${row.locale}) will be removed.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child:
                      Text(row.status == 'APPROVED' ? 'Pause' : 'Remove')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteWhatsAppTemplate(tenant, _senderId!, row.id);
      ref.invalidate(
          whatsAppTemplatesProvider((tenant: tenant, sender: _senderId!)));
      if (mounted) showSnack(context, 'Template removed');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'WhatsApp templates');
    }
    final senders = ref.watch(whatsAppSendersProvider(tenant));

    return AdminTwoPane(
      title: 'WhatsApp templates',
      subtitle:
          'Pre-approved message templates for re-engagement outside the 24-hour '
          'service window. Each template is reviewed by WhatsApp before it can be used.',
      list: senders.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (senderList) {
          final eligible =
              senderList.where((s) => s.status != 'REVOKED').toList();
          if (eligible.isEmpty) {
            return const Center(
                child: Text(
                    'Register a WhatsApp sender first; templates are scoped to each sender.'));
          }
          if (_senderId == null && eligible.isNotEmpty) {
            _senderId = eligible.first.id;
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _senderId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Sender'),
                items: eligible
                    .map((s) => DropdownMenuItem(
                          value: s.id,
                          child: Text('${s.displayName} (${s.phoneE164})'),
                        ))
                    .toList(),
                onChanged: (v) => setState(() => _senderId = v),
              ),
              const SizedBox(height: 12),
              Expanded(child: _templateList(tenant)),
            ],
          );
        },
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _editingId == null ? 'Add template' : 'Editing template #$_editingId',
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
                  : Text(_editingId == null ? 'Add' : 'Save'),
            ),
          ],
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _nameCtrl,
                enabled: _editingId == null,
                decoration: const InputDecoration(
                  labelText: 'Template name',
                  helperText:
                      'Stable identifier you reference from your profile (e.g. appt_reminder).',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _localeCtrl,
                enabled: _editingId == null,
                decoration: const InputDecoration(
                    labelText: 'Locale', helperText: 'BCP-47, e.g. en-US, es-DO.'),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _category,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Category'),
                items: kTemplateCategories
                    .map((c) => DropdownMenuItem(value: c, child: Text(c)))
                    .toList(),
                onChanged: (v) => setState(() => _category = v ?? 'UTILITY'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _bodyCtrl,
                decoration: const InputDecoration(
                  labelText: 'Body',
                  helperText:
                      'Use {{1}}, {{2}}, … for placeholder substitutions. ≤ 1024 chars.',
                ),
                maxLines: 6,
                minLines: 3,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _templateList(String tenant) {
    if (_senderId == null) return const SizedBox.shrink();
    final templates = ref.watch(
        whatsAppTemplatesProvider((tenant: tenant, sender: _senderId!)));
    return templates.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (list) {
        if (list.isEmpty) {
          return const Center(child: Text('No templates yet.'));
        }
        return ListView.separated(
          itemBuilder: (_, i) => _TemplateTile(
            row: list[i],
            onEdit: list[i].isEditable ? () => _loadIntoForm(list[i]) : null,
            onSubmit: list[i].canSubmit ? () => _submit(list[i]) : null,
            onDelete: () => _delete(list[i]),
          ),
          separatorBuilder: (_, i) => const SizedBox(height: 8),
          itemCount: list.length,
        );
      },
    );
  }
}

class _TemplateTile extends StatelessWidget {
  const _TemplateTile({
    required this.row,
    required this.onEdit,
    required this.onSubmit,
    required this.onDelete,
  });

  final WhatsAppTemplateResponse row;
  final VoidCallback? onEdit;
  final VoidCallback? onSubmit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final info = TemplateStatusInfo.of(row.status);
    return EditableRecordCard(
      onCloneEdit: onEdit ?? () {},
      leading: _StatusBadge(status: row.status),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${row.name} (${row.locale})',
              style: Theme.of(context).textTheme.titleSmall),
          Text('${row.category} · ${row.placeholderCount} placeholder${row.placeholderCount == 1 ? '' : 's'}',
              style: const TextStyle(fontSize: 12)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Status', value: '${info.label} — ${info.description}'),
          DetailRow(label: 'Body', value: row.body, mono: true),
          if (row.rejectionReason != null)
            DetailRow(label: 'Rejection reason', value: row.rejectionReason!),
          if (row.submittedAt != null)
            DetailRow(label: 'Submitted', value: row.submittedAt!),
          if (row.approvedAt != null)
            DetailRow(label: 'Approved', value: row.approvedAt!),
        ],
      ),
      extraActions: [
        if (onSubmit != null)
          FilledButton.tonalIcon(
            onPressed: onSubmit,
            icon: const Icon(Icons.send, size: 18),
            label: const Text('Submit for review'),
          ),
        TextButton.icon(
          onPressed: onDelete,
          icon: const Icon(Icons.delete_outline, size: 18),
          label: Text(row.status == 'APPROVED' ? 'Pause' : 'Remove'),
          style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error),
        ),
      ],
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.status});
  final String status;

  Color _bg(ColorScheme s) {
    switch (status) {
      case 'APPROVED':
        return s.primaryContainer;
      case 'SUBMITTED':
        return s.tertiaryContainer;
      case 'REJECTED':
        return s.errorContainer;
      case 'PAUSED':
        return s.secondaryContainer;
      default:
        return s.surfaceContainerHighest;
    }
  }

  Color _fg(ColorScheme s) {
    switch (status) {
      case 'APPROVED':
        return s.onPrimaryContainer;
      case 'SUBMITTED':
        return s.onTertiaryContainer;
      case 'REJECTED':
        return s.onErrorContainer;
      case 'PAUSED':
        return s.onSecondaryContainer;
      default:
        return s.onSurfaceVariant;
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: _bg(scheme),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        TemplateStatusInfo.of(status).label,
        style: TextStyle(
            fontSize: 11, fontWeight: FontWeight.w700, color: _fg(scheme)),
      ),
    );
  }
}
