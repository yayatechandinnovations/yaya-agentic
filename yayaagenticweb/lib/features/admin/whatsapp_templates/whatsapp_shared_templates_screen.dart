import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/whatsapp_template.dart';
import '../shared/admin_shared.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;
import 'template_list_kit.dart';

/// The WhatsApp accounts that carry templates: the platform's, then each one a
/// workspace connected itself.
final whatsAppAccountsProvider =
    FutureProvider<List<WhatsAppAccountResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listWhatsAppAccounts();
});

final whatsAppAccountTemplatesProvider =
    FutureProvider.family<List<WhatsAppAccountTemplate>, String>(
        (ref, accountKey) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listWhatsAppAccountTemplates(accountKey);
});

/// Platform admins: every template once per WhatsApp account, with the
/// workspaces that use it. WhatsApp approves a template per account, so one
/// approval serves every workspace it is shared with.
class WhatsAppSharedTemplatesScreen extends ConsumerStatefulWidget {
  const WhatsAppSharedTemplatesScreen({super.key});

  @override
  ConsumerState<WhatsAppSharedTemplatesScreen> createState() =>
      _WhatsAppSharedTemplatesScreenState();
}

class _WhatsAppSharedTemplatesScreenState
    extends ConsumerState<WhatsAppSharedTemplatesScreen> {
  static final _placeholder = RegExp(r'\{\{(\d+)\}\}');

  final _nameCtrl = TextEditingController();
  final _languageCtrl = TextEditingController(text: 'es');
  final _bodyCtrl = TextEditingController();
  final _samplesCtrl = TextEditingController();
  String _category = 'UTILITY';
  String _accountKey = 'platform';
  bool _saving = false;
  bool _syncing = false;
  TemplateListFilter _filter = const TemplateListFilter();
  final Set<int> _selected = {};

  @override
  void dispose() {
    _nameCtrl.dispose();
    _languageCtrl.dispose();
    _bodyCtrl.dispose();
    _samplesCtrl.dispose();
    super.dispose();
  }

  void _resetForm() {
    setState(() {
      _nameCtrl.clear();
      _bodyCtrl.clear();
      _samplesCtrl.clear();
      _languageCtrl.text = 'es';
      _category = 'UTILITY';
    });
  }

  List<String> get _samples => _samplesCtrl.text
      .split('\n')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .toList();

  int get _placeholderCount => _placeholder
      .allMatches(_bodyCtrl.text)
      .map((m) => int.parse(m.group(1)!))
      .fold<int>(0, (max, n) => n > max ? n : max);

  String? _validate() {
    final name = _nameCtrl.text.trim();
    if (!RegExp(r'^[a-z0-9_]{1,128}$').hasMatch(name)) {
      return 'Template name: lowercase letters, digits and _ only.';
    }
    if (_languageCtrl.text.trim().isEmpty) return 'Language is required.';
    if (_bodyCtrl.text.trim().isEmpty) return 'Template body is required.';
    if (_bodyCtrl.text.length > 1024) {
      return 'Template body must be 1024 characters or fewer.';
    }
    final n = _placeholderCount;
    if (_samples.length != n) {
      return n == 0
          ? 'The body has no placeholders; leave the example values empty.'
          : 'Give $n example value${n == 1 ? '' : 's'}, one per line — one for each placeholder.';
    }
    return null;
  }

  void _refresh() {
    ref.invalidate(whatsAppAccountTemplatesProvider(_accountKey));
    ref.invalidate(whatsAppAccountsProvider);
  }

  Future<void> _run(Future<void> Function(AdminApi api) action, String done) async {
    try {
      final api = await ref.read(adminApiProvider.future);
      await action(api);
      _refresh();
      if (mounted) showSnack(context, done);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _save() async {
    final err = _validate();
    if (err != null) {
      showSnack(context, err, error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.declareWhatsAppAccountTemplate(
          _accountKey,
          WhatsAppTemplateDeclaration(
            name: _nameCtrl.text.trim(),
            language: _languageCtrl.text.trim(),
            category: _category,
            body: _bodyCtrl.text,
            bodySamples: _samples,
          ));
      _refresh();
      if (!mounted) return;
      _resetForm();
      showSnack(context, 'Template saved. Submit it for review, then share it.');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _sync() async {
    setState(() => _syncing = true);
    await _run((api) => api.syncWhatsAppAccountTemplates(_accountKey),
        'Review status refreshed from WhatsApp');
    if (mounted) setState(() => _syncing = false);
  }

  Future<bool> _confirm(String title, String message, String action) async {
    return await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: Text(title),
            content: Text(message),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: Text(action)),
            ],
          ),
        ) ??
        false;
  }

  Future<void> _submit(WhatsAppAccountTemplate row) async {
    final ok = await _confirm(
        'Submit template for review?',
        'WhatsApp reviews each template once for this account. This typically '
            'takes 1–3 business days. The wording cannot change while it is under review.',
        'Submit');
    if (!ok) return;
    await _run((api) => api.submitWhatsAppAccountTemplate(_accountKey, row.id),
        'Template submitted');
  }

  Future<void> _delete(WhatsAppAccountTemplate row) async {
    final ok = await _confirm('Remove this template?',
        '${row.name} (${row.locale}) will be removed from the account.', 'Remove');
    if (!ok) return;
    await _run((api) => api.deleteWhatsAppAccountTemplate(_accountKey, row.id),
        'Template removed');
  }

  Future<void> _unshare(WhatsAppAccountTemplate row, String tenantId) async {
    final ok = await _confirm(
        'Stop sharing with $tenantId?',
        'Workspace $tenantId will no longer be able to send ${row.name} '
            '(${row.locale}). Messages that need it fall back to the chat.',
        'Stop sharing');
    if (!ok) return;
    await _run((api) => api.unshareWhatsAppTemplate(_accountKey, row.id, tenantId),
        'Workspace $tenantId no longer uses the template');
  }

  Future<void> _setHidden(List<int> ids, {required bool hidden}) async {
    await _run(
        (api) => api.setWhatsAppAccountTemplatesHidden(_accountKey, ids, hidden: hidden),
        hidden
            ? '${_count(ids.length)} hidden. «Show hidden» brings them back.'
            : '${_count(ids.length)} back in the list');
    if (mounted) setState(() => _selected.removeAll(ids));
  }

  Future<void> _hideOlder(List<WhatsAppAccountTemplate> rows, Map<int, String> newer) async {
    if (!await confirmHideOlderVersions(context, rows, newer)) return;
    await _setHidden(rows.map((r) => r.id).toList(), hidden: true);
  }

  static String _count(int n) => '$n template${n == 1 ? '' : 's'}';

  static String _accountLabel(WhatsAppAccountResponse a) =>
      '${a.isPlatform ? 'Platform account — every workspace without its own' : '${a.label} — workspace ${a.tenantId}'} '
      '(${_count(a.templateCount)}${a.hiddenCount > 0 ? ', ${a.hiddenCount} hidden' : ''})';

  Future<void> _share(WhatsAppAccountTemplate row, WhatsAppAccountResponse account) async {
    final tenantId = await showDialog<String>(
      context: context,
      builder: (_) => _SharePicker(row: row, account: account),
    );
    if (tenantId == null) return;
    await _run((api) => api.shareWhatsAppTemplate(_accountKey, row.id, tenantId),
        'Shared with workspace $tenantId');
  }

  @override
  Widget build(BuildContext context) {
    final accounts = ref.watch(whatsAppAccountsProvider);
    return AdminTwoPane(
      title: 'Shared WhatsApp templates',
      subtitle:
          'WhatsApp approves a template once per WhatsApp account. Every number on the '
          'platform account shares that approval, so one template can serve many '
          'workspaces: decide here which workspace may send which template.',
      list: accounts.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (accountList) {
          final account = accountList.firstWhere(
              (a) => a.accountKey == _accountKey,
              orElse: () => accountList.first);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: account.accountKey,
                      isExpanded: true,
                      decoration:
                          const InputDecoration(labelText: 'WhatsApp account'),
                      items: accountList
                          .map((a) => DropdownMenuItem(
                                value: a.accountKey,
                                child: Text(_accountLabel(a)),
                              ))
                          .toList(),
                      selectedItemBuilder: (_) => accountList
                          .map((a) => Text(_accountLabel(a),
                              maxLines: 1, overflow: TextOverflow.ellipsis))
                          .toList(),
                      onChanged: (v) => setState(() {
                        _accountKey = v ?? 'platform';
                        _selected.clear();
                      }),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Tooltip(
                    message: 'Ask WhatsApp for the current review status',
                    child: OutlinedButton.icon(
                      onPressed: _syncing ? null : _sync,
                      icon: _syncing
                          ? const SizedBox.square(
                              dimension: 16,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.sync, size: 18),
                      label: const Text('Refresh status'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Expanded(child: _templateList(account)),
            ],
          );
        },
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: 'Add template to this account',
          actions: [
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(
                      dimension: 16,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Add'),
            ),
          ],
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Template name',
                  helperText:
                      'Unique on the account. Lowercase letters, digits and _ (e.g. appt_reminder_v1).',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _languageCtrl,
                decoration: const InputDecoration(
                    labelText: 'Language', helperText: 'e.g. es, en-US.'),
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
                      'Use {{1}}, {{2}}, … for the values filled in when sending. ≤ 1024 chars.',
                ),
                maxLines: 6,
                minLines: 3,
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _samplesCtrl,
                decoration: const InputDecoration(
                  labelText: 'Example values',
                  helperText:
                      'One per line, in order: an example for {{1}}, then {{2}}, … '
                      'WhatsApp reviews the template with them.',
                ),
                maxLines: 5,
                minLines: 2,
              ),
              const SizedBox(height: 12),
              Text(
                'Templates with a document header or buttons are declared by the '
                'integration that sends them; they appear here once declared.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _templateList(WhatsAppAccountResponse account) {
    final templates = ref.watch(whatsAppAccountTemplatesProvider(account.accountKey));
    return templates.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Text(formatError(e),
          style: TextStyle(color: Theme.of(context).colorScheme.error)),
      data: (all) {
        if (all.isEmpty) {
          return const Center(child: Text('No templates on this account yet.'));
        }
        final newer = newerVersions(all);
        final shown = _filter.apply(all);
        final older = all.where((r) => !r.hidden && newer.containsKey(r.id)).toList()
          ..sort(compareTemplates);
        final ids = shown.map((r) => r.id).toSet();
        final selected = _selected.where(ids.contains).toSet();
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
              selectable: ids,
              selected: selected,
              onSelectAll: () => setState(() => _selected.addAll(ids)),
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
                        return _AccountTemplateTile(
                          key: ValueKey(row.id),
                          row: row,
                          newerVersion: newer[row.id],
                          selected: selected.contains(row.id),
                          onSelected: (on) => setState(
                              () => on ? _selected.add(row.id) : _selected.remove(row.id)),
                          onSubmit: row.canSubmit ? () => _submit(row) : null,
                          onShare: () => _share(row, account),
                          onUnshare: (tenantId) => _unshare(row, tenantId),
                          onHide: () => _setHidden([row.id], hidden: !row.hidden),
                          onDelete: row.canDelete ? () => _delete(row) : null,
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

class _AccountTemplateTile extends StatelessWidget {
  const _AccountTemplateTile({
    super.key,
    required this.row,
    required this.newerVersion,
    required this.selected,
    required this.onSelected,
    required this.onSubmit,
    required this.onShare,
    required this.onUnshare,
    required this.onHide,
    required this.onDelete,
  });

  final WhatsAppAccountTemplate row;
  final String? newerVersion;
  final bool selected;
  final ValueChanged<bool> onSelected;
  final VoidCallback? onSubmit;
  final VoidCallback onShare;
  final void Function(String tenantId) onUnshare;
  final VoidCallback onHide;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final info = TemplateStatusInfo.of(row.status);
    final n = row.placeholderCount;
    final users = row.holders.length;
    return TemplateRowCard(
      row: row,
      newerVersion: newerVersion,
      selected: selected,
      onSelected: onSelected,
      subtitle: [
        row.category,
        '$n placeholder${n == 1 ? '' : 's'}',
        if (row.headerKind != 'NONE') '${row.headerKind.toLowerCase()} header',
        if (row.buttons.isNotEmpty) 'buttons: ${row.buttons.join(' · ')}',
      ].join(' · '),
      primaryAction: onSubmit == null
          ? null
          : FilledButton.tonal(onPressed: onSubmit, child: const Text('Submit for review')),
      trailing: Tooltip(
        message: users == 0
            ? 'No workspace can send this template'
            : 'Used by ${row.holders.map((h) => h.tenantId).join(', ')}',
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.groups_outlined,
                size: 18, color: users == 0 ? theme.colorScheme.outline : null),
            const SizedBox(width: 4),
            Text('$users',
                style: TextStyle(color: users == 0 ? theme.colorScheme.outline : null)),
          ],
        ),
      ),
      menu: [
        TemplateAction(row.hidden ? 'Unhide' : 'Hide',
            row.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, onHide),
        if (onDelete != null)
          TemplateAction('Remove', Icons.delete_outline, onDelete!, destructive: true),
      ],
      details: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'Status', value: '${info.label} — ${info.description}'),
          DetailRow(label: 'Body', value: row.body, mono: true),
          if (row.rejectionReason != null)
            DetailRow(label: 'Rejection reason', value: row.rejectionReason!),
          if (newerVersion != null)
            DetailRow(label: 'Replaced by', value: newerVersion!),
          if (row.hidden)
            DetailRow(
                label: 'Hidden',
                value: [
                  if (row.hiddenBy != null) 'by ${row.hiddenBy}',
                  if (row.hiddenAt != null) 'on ${row.hiddenAt}',
                ].join(' ')),
          const SizedBox(height: 8),
          Text('Workspaces that use it', style: theme.textTheme.labelMedium),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (row.holders.isEmpty)
                Text('None yet — no workspace can send this template.',
                    style: theme.textTheme.bodySmall),
              for (final h in row.holders)
                Tooltip(
                  message: 'Shared by ${h.grantedBy}'
                      '${h.grantedAt == null ? '' : ' on ${h.grantedAt}'}',
                  child: InputChip(
                    label: Text(h.tenantId),
                    deleteIcon: const Icon(Icons.close, size: 16),
                    deleteButtonTooltipMessage: 'Stop sharing with ${h.tenantId}',
                    onDeleted: () => onUnshare(h.tenantId),
                  ),
                ),
              ActionChip(
                avatar: const Icon(Icons.add, size: 16),
                label: const Text('Share with a workspace'),
                onPressed: onShare,
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Picks the workspace to share a template with. A workspace's own account
/// only ever serves that workspace.
class _SharePicker extends ConsumerStatefulWidget {
  const _SharePicker({required this.row, required this.account});

  final WhatsAppAccountTemplate row;
  final WhatsAppAccountResponse account;

  @override
  ConsumerState<_SharePicker> createState() => _SharePickerState();
}

class _SharePickerState extends ConsumerState<_SharePicker> {
  String? _tenantId;

  @override
  Widget build(BuildContext context) {
    final tenants = ref.watch(tenantsProvider);
    final held = widget.row.holders.map((h) => h.tenantId).toSet();
    return AlertDialog(
      title: Text('Share ${widget.row.name}'),
      content: SizedBox(
        width: 420,
        child: tenants.when(
          loading: () => const SizedBox(
              height: 80, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(formatError(e),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
          data: (all) {
            final candidates = all
                .where((t) => !held.contains(t.id))
                .where((t) =>
                    widget.account.isPlatform || t.id == widget.account.tenantId)
                .toList()
              ..sort((x, y) => x.id.compareTo(y.id));
            if (candidates.isEmpty) {
              return Text(widget.account.isPlatform
                  ? 'Every workspace already uses this template.'
                  : 'This account belongs to workspace ${widget.account.tenantId}, '
                      'which already uses this template.');
            }
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text(
                    'The workspace can send this template from its numbers on this '
                    'account. No new WhatsApp review is needed.'),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _tenantId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Workspace'),
                  items: candidates
                      .map((t) => DropdownMenuItem(
                            value: t.id,
                            child: Text(t.displayName == t.id
                                ? t.id
                                : '${t.displayName} (${t.id})'),
                          ))
                      .toList(),
                  onChanged: (v) => setState(() => _tenantId = v),
                ),
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
            onPressed:
                _tenantId == null ? null : () => Navigator.pop(context, _tenantId),
            child: const Text('Share')),
      ],
    );
  }
}
