import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../auth/application/auth_controller.dart';
import '../../auth/models/auth_state.dart';
import '../../../models/admin/whatsapp_template.dart';
import '../shared/admin_shared.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart' show whatsAppSendersProvider;
import 'template_list_kit.dart';

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
  TemplateListFilter _filter = const TemplateListFilter();
  final Set<int> _selected = {};

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
            title: Text(row.staysInAccount
                ? 'Stop using this template?'
                : 'Remove this template?'),
            content: Text(row.staysInAccount
                ? 'This workspace will no longer be able to send '
                    '${row.name} (${row.locale}). The template itself stays '
                    'on the WhatsApp account'
                    '${row.shared ? ' for the other workspaces that use it' : ''}; '
                    'a platform admin can share it with this workspace again.'
                : '${row.name} (${row.locale}) will be removed.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: Text(row.staysInAccount ? 'Stop using' : 'Remove')),
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
      if (mounted) {
        showSnack(context,
            row.staysInAccount ? 'This workspace no longer uses the template' : 'Template removed');
      }
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _setHidden(List<int> ids, {required bool hidden}) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null || _senderId == null || ids.isEmpty) return;
    final n = '${ids.length} template${ids.length == 1 ? '' : 's'}';
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.setWhatsAppTemplatesHidden(tenant, _senderId!, ids, hidden: hidden);
      ref.invalidate(
          whatsAppTemplatesProvider((tenant: tenant, sender: _senderId!)));
      if (!mounted) return;
      setState(() => _selected.removeAll(ids));
      showSnack(context,
          hidden ? '$n hidden. «Show hidden» brings them back.' : '$n back in the list');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _hideOlder(List<WhatsAppTemplateResponse> rows, Map<int, String> newer) async {
    if (!await confirmHideOlderVersions(context, rows, newer)) return;
    await _setHidden(rows.map((r) => r.id).toList(), hidden: true);
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
          'service window. Each template is reviewed by WhatsApp once per WhatsApp '
          'account; a platform admin can share an approved one with other workspaces.',
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
                    'Register a WhatsApp sender first; templates live on the WhatsApp account a sender uses.'));
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
                onChanged: (v) => setState(() {
                  _senderId = v;
                  _selected.clear();
                }),
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
    final auth = ref.watch(authProvider);
    final platformAdmin =
        auth is AuthAuthenticated && auth.operator.platformAdmin;
    // Hiding marks the template itself, so one other workspaces use is a
    // platform admin's to hide — as its wording is theirs to change.
    bool canHide(WhatsAppTemplateResponse r) => !r.shared || platformAdmin;
    final templates = ref.watch(
        whatsAppTemplatesProvider((tenant: tenant, sender: _senderId!)));
    return templates.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (all) {
        if (all.isEmpty) {
          return const Center(child: Text('No templates yet.'));
        }
        final newer = newerVersions(all);
        final shown = _filter.apply(all);
        final older = all
            .where((r) => !r.hidden && newer.containsKey(r.id) && canHide(r))
            .toList()
          ..sort(compareTemplates);
        final selectable = shown.where(canHide).map((r) => r.id).toSet();
        final selected = _selected.where(selectable.contains).toSet();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TemplateListToolbar(
              rows: all,
              filter: _filter,
              onChanged: (f) => setState(() => _filter = f),
            ),
            if (older.isNotEmpty) ...[
              const SizedBox(height: 8),
              OlderVersionsBanner(rows: older, onHide: () => _hideOlder(older, newer)),
            ],
            const SizedBox(height: 4),
            TemplateSelectionBar(
              shown: shown,
              total: all.length,
              selectable: selectable,
              selected: selected,
              onSelectAll: () => setState(() => _selected.addAll(selectable)),
              onClear: () => setState(_selected.clear),
              onHide: () => _setHidden(
                  shown.where((r) => selected.contains(r.id) && !r.hidden).map((r) => r.id).toList(),
                  hidden: true),
              onUnhide: () => _setHidden(
                  shown.where((r) => selected.contains(r.id) && r.hidden).map((r) => r.id).toList(),
                  hidden: false),
            ),
            const SizedBox(height: 4),
            Expanded(
              child: shown.isEmpty
                  ? const Center(child: Text('No template matches.'))
                  : ListView.separated(
                      itemBuilder: (_, i) {
                        final row = shown[i];
                        return _TemplateTile(
                          key: ValueKey(row.id),
                          row: row,
                          newerVersion: newer[row.id],
                          selected: selected.contains(row.id),
                          onSelected: canHide(row)
                              ? (on) => setState(() =>
                                  on ? _selected.add(row.id) : _selected.remove(row.id))
                              : null,
                          // Wording shared with other workspaces is a platform admin's to change.
                          onEdit: row.isEditable && (!row.shared || platformAdmin)
                              ? () => _loadIntoForm(row)
                              : null,
                          onSubmit: row.canSubmit ? () => _submit(row) : null,
                          onHide: canHide(row)
                              ? () => _setHidden([row.id], hidden: !row.hidden)
                              : null,
                          onDelete: () => _delete(row),
                        );
                      },
                      separatorBuilder: (_, i) => const SizedBox(height: 6),
                      itemCount: shown.length,
                    ),
            ),
          ],
        );
      },
    );
  }
}

class _TemplateTile extends StatelessWidget {
  const _TemplateTile({
    super.key,
    required this.row,
    required this.newerVersion,
    required this.selected,
    required this.onSelected,
    required this.onEdit,
    required this.onSubmit,
    required this.onHide,
    required this.onDelete,
  });

  final WhatsAppTemplateResponse row;
  final String? newerVersion;
  final bool selected;
  final ValueChanged<bool>? onSelected;
  final VoidCallback? onEdit;
  final VoidCallback? onSubmit;
  final VoidCallback? onHide;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final info = TemplateStatusInfo.of(row.status);
    final n = row.placeholderCount;
    return TemplateRowCard(
      row: row,
      newerVersion: newerVersion,
      selected: selected,
      onSelected: onSelected,
      notSelectableReason:
          'Other workspaces use this template too; a platform admin can hide it.',
      subtitle: '${row.category} · $n placeholder${n == 1 ? '' : 's'}',
      tags: [
        if (row.shared)
          const TemplateTag(
            label: 'Shared',
            icon: Icons.groups_outlined,
            tooltip: 'Other workspaces on the same WhatsApp account use this template too.',
          ),
      ],
      primaryAction: onSubmit == null
          ? null
          : FilledButton.tonal(onPressed: onSubmit, child: const Text('Submit for review')),
      menu: [
        if (onEdit != null) TemplateAction('Edit', Icons.edit_outlined, onEdit!),
        if (onHide != null)
          TemplateAction(row.hidden ? 'Unhide' : 'Hide',
              row.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, onHide!),
        TemplateAction(row.staysInAccount ? 'Stop using' : 'Remove', Icons.delete_outline, onDelete,
            destructive: true),
      ],
      details: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Status', value: '${info.label} — ${info.description}'),
          DetailRow(label: 'Body', value: row.body, mono: true),
          if (row.rejectionReason != null)
            DetailRow(label: 'Rejection reason', value: row.rejectionReason!),
          if (newerVersion != null) DetailRow(label: 'Replaced by', value: newerVersion!),
          if (row.submittedAt != null)
            DetailRow(label: 'Submitted', value: row.submittedAt!),
          if (row.approvedAt != null)
            DetailRow(label: 'Approved', value: row.approvedAt!),
        ],
      ),
    );
  }
}
