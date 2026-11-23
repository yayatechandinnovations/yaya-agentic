import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../models/admin/whatsapp_template.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';
import '../tenants/tenants_screen.dart' show tenantsProvider;
import 'template_list_kit.dart';

/// The WhatsApp accounts that carry templates: the platform's, then each one a
/// workspace connected itself.
final whatsAppAccountsProvider = FutureProvider<List<WhatsAppAccountResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.whatsappTemplate);
  final api = await ref.watch(adminApiProvider.future);
  return api.listWhatsAppAccounts();
});

final whatsAppAccountTemplatesProvider =
    FutureProvider.family<List<WhatsAppAccountTemplate>, String>((ref, accountKey) async {
  liveResource(ref, AdminResourceKind.whatsappTemplate);
  final api = await ref.watch(adminApiProvider.future);
  final rows = await api.listWhatsAppAccountTemplates(accountKey);
  // WhatsApp's verdict arrives by callback or the server's periodic sync,
  // neither of which the change feed sees.
  pollWhile(ref, anyPendingReview(rows), every: const Duration(seconds: 20));
  return rows;
});

const _routePath = '/admin/whatsapp-shared-templates';

Map<String, String> _routeParams(BuildContext context) =>
    GoRouter.maybeOf(context) == null ? const {} : GoRouterState.of(context).uri.queryParameters;

String _count(int n) => '$n template${n == 1 ? '' : 's'}';

/// Platform admins: every template once per WhatsApp account, with the
/// workspaces that use it. WhatsApp approves a template per account, so one
/// approval serves every workspace it is shared with.
class WhatsAppSharedTemplatesScreen extends ConsumerStatefulWidget {
  const WhatsAppSharedTemplatesScreen({super.key});

  @override
  ConsumerState<WhatsAppSharedTemplatesScreen> createState() => _WhatsAppSharedTemplatesScreenState();
}

class _WhatsAppSharedTemplatesScreenState extends ConsumerState<WhatsAppSharedTemplatesScreen> {
  String _accountKey = 'platform';
  WhatsAppAccountResponse? _account;
  bool _syncing = false;
  bool _showHidden = false;
  final Set<int> _selected = {};
  String? _handledLink;

  void _refresh() {
    ref.invalidate(whatsAppAccountTemplatesProvider(_accountKey));
    ref.invalidate(whatsAppAccountsProvider);
  }

  /// Runs [action]; on success refreshes and says [done]. Errors become a toast.
  Future<bool> _run(BuildContext context, Future<void> Function(AdminApi api) action, String done,
      {String? undoLabel, VoidCallback? onUndo}) async {
    try {
      final api = await ref.read(adminApiProvider.future);
      await action(api);
      _refresh();
      if (context.mounted) showToast(context, done, actionLabel: undoLabel, onAction: onUndo);
      return true;
    } catch (e) {
      if (context.mounted) showToast(context, formatError(e), error: true);
      return false;
    }
  }

  Future<void> _sync() async {
    setState(() => _syncing = true);
    await _run(context, (api) => api.syncWhatsAppAccountTemplates(_accountKey), 'Review status refreshed from WhatsApp');
    if (mounted) setState(() => _syncing = false);
  }

  Future<void> _openEditor() async {
    final account = _account;
    if (account == null) return;
    await openTemplateSheet<void>(
      context,
      width: 960,
      label: 'New shared WhatsApp template',
      builder: (_) => AccountTemplateEditorSheet(account: account),
    );
  }

  Future<void> _openDetail(WhatsAppAccountTemplate row) async {
    final account = _account;
    if (account == null) return;
    await openTemplateSheet<void>(
      context,
      width: 720,
      label: 'WhatsApp template',
      builder: (sheetContext) => _AccountTemplateDetailSheet(
        account: account,
        id: row.id,
        onSubmit: (r) => _submit(sheetContext, r),
        onShare: (r) => _share(sheetContext, [r], account),
        onUnshare: (r, tenantId) => _unshare(sheetContext, r, tenantId),
        onHide: (r) => _setHidden(sheetContext, [r], hidden: !r.hidden),
        onDelete: (r) async {
          if (await _delete(sheetContext, r) && sheetContext.mounted) Navigator.of(sheetContext).pop();
        },
      ),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the sheet once the list is in.
  void _handleDeepLink(List<WhatsAppAccountTemplate> list) {
    final params = _routeParams(context);
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((r) => '${r.id}' == link || r.name == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No template “$link” on this account', error: true);
      } else if (match != null) {
        await _openDetail(match);
      } else {
        await _openEditor();
      }
      if (mounted) GoRouter.maybeOf(context)?.go(_routePath);
      _handledLink = null;
    });
  }

  Future<void> _submit(BuildContext context, WhatsAppAccountTemplate row) async {
    final ok = await confirmAction(
      context,
      title: 'Submit ${row.name} for review?',
      message: 'WhatsApp reviews each template once for this account. This typically takes 1–3 business days. '
          'The wording can’t change while it’s under review.',
      confirmLabel: 'Submit for review',
    );
    if (!ok || !context.mounted) return;
    await _run(context, (api) => api.submitWhatsAppAccountTemplate(_accountKey, row.id),
        'Submitted ${row.name}. This list updates when WhatsApp answers.');
  }

  Future<bool> _delete(BuildContext context, WhatsAppAccountTemplate row) async {
    final ok = await confirmAction(
      context,
      destructive: true,
      title: 'Remove ${row.name}?',
      message: '${row.name} (${row.locale}) will be removed from the account. No workspace uses it and WhatsApp '
          'never approved it, so nothing that sends today is affected.',
      confirmLabel: 'Remove',
    );
    if (!ok || !context.mounted) return false;
    return _run(context, (api) => api.deleteWhatsAppAccountTemplate(_accountKey, row.id), 'Removed ${row.name}');
  }

  Future<void> _unshare(BuildContext context, WhatsAppAccountTemplate row, String tenantId) async {
    final ok = await confirmAction(
      context,
      destructive: true,
      title: 'Stop sharing with $tenantId?',
      message: 'Workspace $tenantId will no longer be able to send ${row.name} (${row.locale}). '
          'Messages that need it fall back to the chat.',
      confirmLabel: 'Stop sharing',
    );
    if (!ok || !context.mounted) return;
    await _run(context, (api) => api.unshareWhatsAppTemplate(_accountKey, row.id, tenantId),
        'Workspace $tenantId no longer uses ${row.name}');
  }

  Future<void> _setHidden(BuildContext context, List<WhatsAppAccountTemplate> rows,
      {required bool hidden, bool undoable = true}) async {
    if (rows.isEmpty) return;
    final ids = rows.map((r) => r.id).toList();
    final what = rows.length == 1 ? rows.single.name : _count(rows.length);
    final done = await _run(
      context,
      (api) => api.setWhatsAppAccountTemplatesHidden(_accountKey, ids, hidden: hidden),
      hidden ? 'Hid $what. «Show hidden» brings ${rows.length == 1 ? 'it' : 'them'} back.' : '$what back in the list',
      undoLabel: undoable ? 'Undo' : null,
      onUndo: undoable ? () => _setHidden(this.context, rows, hidden: !hidden, undoable: false) : null,
    );
    if (done && mounted) setState(() => _selected.removeAll(ids));
  }

  Future<void> _hideOlder(List<WhatsAppAccountTemplate> rows, Map<int, String> newer) async {
    if (!await confirmHideOlderVersions(context, rows, newer)) return;
    if (mounted) await _setHidden(context, rows, hidden: true);
  }

  Future<void> _share(BuildContext context, List<WhatsAppAccountTemplate> rows, WhatsAppAccountResponse account) async {
    final tenantIds = await showDialog<List<String>>(
      context: context,
      builder: (_) => _SharePicker(rows: rows, account: account),
    );
    if (tenantIds == null || tenantIds.isEmpty || !context.mounted) return;
    final ids = rows.map((r) => r.id).toList();
    final what = rows.length == 1 ? rows.single.name : _count(rows.length);
    final whom = tenantIds.length == 1 ? 'workspace ${tenantIds.single}' : '${tenantIds.length} workspaces';
    final done = await _run(context, (api) => api.shareWhatsAppTemplates(_accountKey, ids, tenantIds), 'Shared $what with $whom');
    if (done && mounted) setState(() => _selected.removeAll(ids));
  }

  static String _accountLabel(WhatsAppAccountResponse a) =>
      a.isPlatform ? 'Platform account' : '${a.label} — workspace ${a.tenantId}';

  static String _accountMeta(WhatsAppAccountResponse a) =>
      '${_count(a.templateCount)}${a.hiddenCount > 0 ? ' · ${a.hiddenCount} hidden' : ''}';

  @override
  Widget build(BuildContext context) {
    final accounts = ref.watch(whatsAppAccountsProvider);
    final list = accounts.valueOrNull ?? const <WhatsAppAccountResponse>[];
    _account = list.where((a) => a.accountKey == _accountKey).firstOrNull ?? list.firstOrNull;
    if (_account != null) _accountKey = _account!.accountKey;

    return YPage(
      title: 'Shared WhatsApp templates',
      subtitle: 'WhatsApp approves a template once per WhatsApp account. Every number on the platform account shares '
          'that approval, so one template can serve many workspaces: decide here which workspace may send which.',
      actions: [
        Tooltip(
          message: 'Ask WhatsApp for the current review status',
          child: OutlinedButton.icon(
            onPressed: _syncing || _account == null ? null : _sync,
            icon: _syncing
                ? const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.sync, size: 18),
            label: const Text('Refresh status'),
          ),
        ),
        FilledButton.icon(
          onPressed: _account == null ? null : _openEditor,
          icon: const Icon(Icons.add, size: 18),
          label: const Text('New template'),
        ),
      ],
      child: LiveAsyncView<List<WhatsAppAccountResponse>>(
        value: accounts,
        what: 'WhatsApp accounts',
        onRetry: () => ref.invalidate(whatsAppAccountsProvider),
        data: (context, all) {
          final account = _account;
          if (account == null) {
            return const YEmptyState(
              icon: Icons.chat_outlined,
              title: 'No WhatsApp account yet',
              message: 'Templates live on a WhatsApp account. Connect the platform account, or let a workspace connect its own.',
            );
          }
          return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Align(
              alignment: Alignment.centerLeft,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: YSelectField<String>(
                  label: 'WhatsApp account',
                  wireKey: 'accountKey',
                  helper: account.isPlatform
                      ? 'Every workspace without its own WhatsApp account sends from this one.'
                      : 'This account belongs to workspace ${account.tenantId}; its templates serve only that workspace.',
                  value: account.accountKey,
                  options: [for (final a in all) YOption(a.accountKey, _accountLabel(a), meta: _accountMeta(a))],
                  onChanged: (v) => setState(() {
                    _accountKey = v ?? 'platform';
                    _selected.clear();
                  }),
                ),
              ),
            ),
            const SizedBox(height: Gap.lg),
            Expanded(child: _list(account)),
          ]);
        },
      ),
    );
  }

  Widget _list(WhatsAppAccountResponse account) {
    final templates = ref.watch(whatsAppAccountTemplatesProvider(account.accountKey));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.whatsappTemplate));
    return LiveAsyncView<List<WhatsAppAccountTemplate>>(
      value: templates,
      what: 'templates',
      onRetry: () => ref.invalidate(whatsAppAccountTemplatesProvider(account.accountKey)),
      data: (context, all) {
        _handleDeepLink(all);
        final newer = newerVersions(all);
        final listed = listedTemplates(all, showHidden: _showHidden);
        final older = all.where((r) => !r.hidden && newer.containsKey(r.id)).toList()..sort(compareTemplates);
        final selectable = listed.map((r) => r.id).toSet();
        final selected = listed.where((r) => _selected.contains(r.id)).toList();
        final hiddenCount = all.where((r) => r.hidden).length;
        return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (older.isNotEmpty) ...[
            OlderVersionsBanner(rows: older, onHide: () => _hideOlder(older, newer)),
            const SizedBox(height: Gap.md),
          ],
          if (selected.isNotEmpty) ...[
            TemplateSelectionBar(
              selected: selected,
              onClear: () => setState(_selected.clear),
              onHide: () => _setHidden(context, selected.where((r) => !r.hidden).toList(), hidden: true),
              onUnhide: () => _setHidden(context, selected.where((r) => r.hidden).toList(), hidden: false),
              onShare: () => _share(context, selected, account),
            ),
            const SizedBox(height: Gap.md),
          ],
          Expanded(
            child: TemplateTable<WhatsAppAccountTemplate>(
              rows: listed,
              newer: newer,
              selected: _selected,
              onSelected: (r, on) => setState(() => on ? _selected.add(r.id) : _selected.remove(r.id)),
              highlightIds: fresh,
              searchTextOf: (r) =>
                  '${r.name} ${r.locale} ${r.category} ${r.body} ${r.holders.map((h) => h.tenantId).join(' ')}',
              subtitleOf: (r) => [
                templateCategoryLabel(r.category),
                '${r.placeholderCount} variable${r.placeholderCount == 1 ? '' : 's'}',
                if (r.headerKind != 'NONE') '${r.headerKind.toLowerCase()} header',
                if (r.buttons.isNotEmpty) 'buttons: ${r.buttons.join(' · ')}',
              ].join(' · '),
              primaryActionOf: (r) => r.canSubmit
                  ? FilledButton.tonal(onPressed: () => _submit(context, r), child: const Text('Submit for review'))
                  : null,
              extraColumns: [
                YColumn<WhatsAppAccountTemplate>(
                  label: 'Workspaces',
                  width: 150,
                  sortKey: (r) => r.holders.length,
                  cell: (context, r) => _HolderCount(row: r),
                ),
              ],
              menuOf: (r) => [
                TemplateAction('Share…', Icons.group_add_outlined, () => _share(context, [r], account)),
                TemplateAction(r.hidden ? 'Unhide' : 'Hide',
                    r.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                    () => _setHidden(context, [r], hidden: !r.hidden)),
                if (r.canDelete)
                  TemplateAction('Remove', Icons.delete_outline, () => _delete(context, r), destructive: true),
              ],
              onRowTap: _openDetail,
              toolbarTrailing: [
                if (hiddenCount > 0 || _showHidden)
                  ShowHiddenChip(count: hiddenCount, on: _showHidden, onChanged: (v) => setState(() => _showHidden = v)),
                SelectAllButton(
                  selectable: selectable,
                  selected: _selected,
                  onSelectAll: () => setState(() => _selected.addAll(selectable)),
                  onClear: () => setState(_selected.clear),
                ),
              ],
              empty: all.isEmpty
                  ? templatesEmptyState(
                      message: 'Declare a template once on this account, have WhatsApp approve it, then share it with '
                          'the workspaces that may send it.',
                      action: FilledButton.icon(
                        onPressed: _openEditor,
                        icon: const Icon(Icons.add, size: 18),
                        label: const Text('Create the first template'),
                      ),
                    )
                  : YEmptyState(
                      icon: Icons.visibility_off_outlined,
                      title: 'Every template here is hidden',
                      message: 'Hidden templates still send; they are only left out of this list.',
                      action: OutlinedButton(
                        onPressed: () => setState(() => _showHidden = true),
                        child: Text('Show hidden ($hiddenCount)'),
                      ),
                    ),
            ),
          ),
        ]);
      },
    );
  }
}

class _HolderCount extends StatelessWidget {
  const _HolderCount({required this.row});
  final WhatsAppAccountTemplate row;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final users = row.holders.length;
    final color = users == 0 ? scheme.onSurfaceVariant : scheme.onSurface;
    return Tooltip(
      message: users == 0
          ? 'No workspace can send this template'
          : 'Used by ${row.holders.map((h) => h.tenantId).join(', ')}',
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.groups_outlined, size: 18, color: color),
        const SizedBox(width: 6),
        Text(users == 0 ? 'None' : '$users', style: TextStyle(fontSize: 14, color: color)),
      ]),
    );
  }
}

/// One account template, read-only, with who may send it.
class _AccountTemplateDetailSheet extends ConsumerWidget {
  const _AccountTemplateDetailSheet({
    required this.account,
    required this.id,
    required this.onSubmit,
    required this.onShare,
    required this.onUnshare,
    required this.onHide,
    required this.onDelete,
  });

  final WhatsAppAccountResponse account;
  final int id;
  final ValueChanged<WhatsAppAccountTemplate> onSubmit;
  final ValueChanged<WhatsAppAccountTemplate> onShare;
  final void Function(WhatsAppAccountTemplate row, String tenantId) onUnshare;
  final ValueChanged<WhatsAppAccountTemplate> onHide;
  final ValueChanged<WhatsAppAccountTemplate> onDelete;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final all = ref.watch(whatsAppAccountTemplatesProvider(account.accountKey)).valueOrNull ??
        const <WhatsAppAccountTemplate>[];
    final row = all.where((r) => r.id == id).firstOrNull;
    if (row == null) {
      return const YSheetScaffold(
        title: 'Template not found',
        body: YEmptyState(
          icon: Icons.delete_outline,
          title: 'This template is gone',
          message: 'It was removed from the account.',
        ),
      );
    }
    final info = TemplateStatusInfo.of(row.status);
    final newer = newerVersions(all)[row.id];
    final n = row.placeholderCount;
    return YSheetScaffold(
      title: row.name,
      subtitle: '${row.locale} · ${templateCategoryLabel(row.category)} · '
          '${account.isPlatform ? 'platform account' : 'account of workspace ${account.tenantId}'}',
      body: ListView(
        padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
        children: [
          Row(children: [
            TemplateStatusBadge(status: row.status),
            const SizedBox(width: 10),
            Expanded(child: Text(info.description, style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant))),
          ]),
          if (row.status == 'REJECTED' && (row.rejectionReason ?? '').isNotEmpty) ...[
            const SizedBox(height: Gap.md),
            YBanner(tone: YBannerTone.error, title: 'WhatsApp’s reason:', message: row.rejectionReason!),
          ],
          if (newer != null) ...[
            const SizedBox(height: Gap.md),
            YBanner(tone: YBannerTone.info, message: 'A newer version, $newer, replaces this one.'),
          ],
          const SizedBox(height: Gap.xl),
          Text('Workspaces that use it', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
            if (row.holders.isEmpty)
              Text('None yet — no workspace can send this template.',
                  style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant)),
            for (final h in row.holders)
              Tooltip(
                message: 'Shared by ${h.grantedBy}${h.grantedAt == null ? '' : ' ${formatRelativeIso(h.grantedAt)}'}',
                child: InputChip(
                  label: Text(h.tenantId, style: monoStyle(context, size: 12.5)),
                  deleteIcon: const Icon(Icons.close, size: 16),
                  deleteButtonTooltipMessage: 'Stop sharing with ${h.tenantId}',
                  onDeleted: () => onUnshare(row, h.tenantId),
                ),
              ),
            ActionChip(
              avatar: const Icon(Icons.add, size: 16),
              label: const Text('Share with a workspace'),
              onPressed: () => onShare(row),
            ),
          ]),
          const SizedBox(height: Gap.xl),
          WhatsAppBodyPreview(body: row.body),
          const SizedBox(height: Gap.xl),
          TemplateFact('Category', templateCategoryLabel(row.category)),
          TemplateFact('Variables', n == 0 ? 'None' : '$n — a send supplies {{1}}${n > 1 ? '…{{$n}}' : ''}'),
          if (row.headerKind != 'NONE') TemplateFact('Header', '${row.headerKind.toLowerCase()} header'),
          if (row.buttons.isNotEmpty) TemplateFact('Buttons', row.buttons.join(' · ')),
          if (row.submittedAt != null) TemplateFact('Submitted', formatRelativeIso(row.submittedAt)),
          if (row.approvedAt != null) TemplateFact('Approved', formatRelativeIso(row.approvedAt)),
          if (row.lastSyncAt != null) TemplateFact('Status checked', formatRelativeIso(row.lastSyncAt)),
          if (row.hidden)
            TemplateFact(
                'Hidden',
                [
                  if (row.hiddenBy != null) 'by ${row.hiddenBy}',
                  if (row.hiddenAt != null) formatRelativeIso(row.hiddenAt),
                ].join(' ')),
          TemplateFact('Template ID', '${row.id}', mono: true),
          if (row.contentSid != null) TemplateFact('Content SID', row.contentSid!, mono: true),
        ],
      ),
      footer: TemplateSheetFooter(children: [
        if (row.canDelete) DangerButton(label: 'Remove', onPressed: () => onDelete(row)),
        OutlinedButton.icon(
          onPressed: () => onHide(row),
          icon: Icon(row.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
          label: Text(row.hidden ? 'Unhide' : 'Hide'),
        ),
        if (row.canSubmit) FilledButton(onPressed: () => onSubmit(row), child: const Text('Submit for review')),
      ]),
    );
  }
}

/// Picks the workspaces to share templates with. A workspace's own account
/// only ever serves that workspace.
class _SharePicker extends ConsumerStatefulWidget {
  const _SharePicker({required this.rows, required this.account});

  final List<WhatsAppAccountTemplate> rows;
  final WhatsAppAccountResponse account;

  @override
  ConsumerState<_SharePicker> createState() => _SharePickerState();
}

class _SharePickerState extends ConsumerState<_SharePicker> {
  final _chosen = <String>{};
  String _query = '';

  /// How many of the templates the workspace already uses.
  int _holding(String tenantId) => widget.rows.where((r) => r.holders.any((h) => h.tenantId == tenantId)).length;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final tenants = ref.watch(tenantsProvider);
    final rows = widget.rows;
    final single = rows.length == 1;
    final n = _chosen.length;
    return AlertDialog(
      title: Text(single ? 'Share ${rows.single.name}' : 'Share ${rows.length} templates'),
      content: SizedBox(
        width: 460,
        child: tenants.when(
          loading: () => const SizedBox(height: 80, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(formatError(e), style: TextStyle(fontSize: 14, color: scheme.error)),
          data: (all) {
            final candidates = all
                .where((t) => t.status != 'ARCHIVED')
                .where((t) => widget.account.isPlatform || t.id == widget.account.tenantId)
                .toList()
              ..sort((x, y) => x.id.compareTo(y.id));
            final q = _query.trim().toLowerCase();
            final listed = candidates
                .where((t) => q.isEmpty || t.id.toLowerCase().contains(q) || t.displayName.toLowerCase().contains(q))
                .toList();
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  single
                      ? 'The workspaces you pick can send this template from their numbers on this account. '
                          'No new WhatsApp review is needed.'
                      : 'Each workspace you pick can send all ${rows.length} from its numbers on this account. '
                          'No new WhatsApp review is needed.',
                  style: const TextStyle(fontSize: 14, height: 1.5),
                ),
                if (!widget.account.isPlatform)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'This account belongs to workspace ${widget.account.tenantId}; its templates serve only that workspace.',
                      style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
                    ),
                  ),
                if (candidates.length > 8) ...[
                  const SizedBox(height: 12),
                  TextField(
                    decoration: const InputDecoration(
                      isDense: true,
                      prefixIcon: Icon(Icons.search, size: 20),
                      hintText: 'Find a workspace',
                    ),
                    onChanged: (v) => setState(() => _query = v),
                  ),
                ],
                const SizedBox(height: 8),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 320),
                  child: ListView(
                    shrinkWrap: true,
                    children: [for (final t in listed) _tile(t.id, t.displayName)],
                  ),
                ),
              ],
            );
          },
        ),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: n == 0 ? null : () => Navigator.pop(context, _chosen.toList()..sort()),
          child: Text(n == 0 ? 'Share' : 'Share with $n workspace${n == 1 ? '' : 's'}'),
        ),
      ],
    );
  }

  Widget _tile(String id, String displayName) {
    final total = widget.rows.length;
    final has = _holding(id);
    // Nothing left to share with a workspace that already uses them all.
    final complete = has == total;
    return CheckboxListTile(
      dense: true,
      controlAffinity: ListTileControlAffinity.leading,
      value: complete || _chosen.contains(id),
      onChanged: complete ? null : (v) => setState(() => v == true ? _chosen.add(id) : _chosen.remove(id)),
      title: Text(displayName == id ? id : '$displayName ($id)', style: const TextStyle(fontSize: 14)),
      subtitle: has == 0
          ? null
          : Text(complete ? (total == 1 ? 'Already uses it' : 'Already uses all $total') : 'Already uses $has of $total',
              style: const TextStyle(fontSize: 13)),
    );
  }
}

const _labels = {
  'name': 'Template name',
  'language': 'Language',
  'category': 'Category',
  'body': 'Message',
  'samples': 'Example values',
};

const _fieldByCode = {
  'bad_name': 'name',
  'name_mismatch': 'name',
  'template_exists': 'name',
  'not_editable': 'name',
  'bad_body': 'body',
  'bad_placeholders': 'body',
  'bad_samples': 'samples',
};

/// Declare a text-only template on an account. Templates with a document
/// header or buttons are declared by the integration that sends them.
class AccountTemplateEditorSheet extends ConsumerStatefulWidget {
  const AccountTemplateEditorSheet({super.key, required this.account});
  final WhatsAppAccountResponse account;

  @override
  ConsumerState<AccountTemplateEditorSheet> createState() => _AccountTemplateEditorSheetState();
}

class _AccountTemplateEditorSheetState extends ConsumerState<AccountTemplateEditorSheet> {
  static final _templateName = RegExp(r'^[a-z0-9_]{1,128}$');

  final _name = TextEditingController();
  final _language = TextEditingController(text: 'es');
  final _body = TextEditingController();
  final List<TextEditingController> _samples = [];
  String _category = 'UTILITY';
  late final YFormController _form;

  int get _n => placeholderCount(_body.text);

  List<String> get _sampleValues => [for (var i = 0; i < _n; i++) i < _samples.length ? _samples[i].text.trim() : ''];

  @override
  void initState() {
    super.initState();
    _form = YFormController(
      snapshot: () => {
        'name': _name.text.trim(),
        'language': _language.text.trim(),
        'category': _category,
        'body': _body.text,
        'samples': _sampleValues,
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.whatsappAccountTemplate.${widget.account.accountKey}.new',
    );
    _form.addListener(_rebuild);
    _body.addListener(_syncSamples);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _name.text = (v['name'] as String?) ?? _name.text;
      _language.text = (v['language'] as String?) ?? _language.text;
      _body.text = (v['body'] as String?) ?? _body.text;
      final cat = v['category'];
      if (cat is String && kTemplateCategories.contains(cat)) _category = cat;
      final samples = v['samples'];
      if (samples is List) {
        _syncSamples();
        for (var i = 0; i < samples.length && i < _samples.length; i++) {
          _samples[i].text = '${samples[i]}';
        }
      }
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  /// One example field per `{{n}}`; values typed for a variable that goes
  /// away are kept in case it comes back.
  void _syncSamples() {
    while (_samples.length < _n) {
      _samples.add(TextEditingController());
    }
    _rebuild();
  }

  Map<String, String?> _validate() {
    final name = _name.text.trim();
    final language = _language.text.trim();
    String? nameErr = Validators.required(name, 'a template name');
    if (nameErr == null && !_templateName.hasMatch(name)) {
      nameErr = 'Use lowercase letters, digits and _ only (up to 128), e.g. appt_reminder_v1.';
    }
    final existing = ref.read(whatsAppAccountTemplatesProvider(widget.account.accountKey)).valueOrNull ??
        const <WhatsAppAccountTemplate>[];
    // Declaring an existing name replaces its wording (and drops a header or
    // buttons an integration declared), so a new wording takes a new name.
    if (nameErr == null && existing.any((r) => r.name == name && r.locale == language)) {
      final v = TemplateVersion.of(name);
      nameErr = '$name ($language) is already on this account. Use a new name, e.g. ${v.family}_v${v.number + 1}.';
    }
    final body = _body.text;
    String? bodyErr = Validators.required(body, 'the message wording');
    if (bodyErr == null && body.length > 1024) bodyErr = 'Keep the message to 1024 characters (it has ${body.length}).';
    bodyErr ??= placeholderIssues(body).where((i) => i.declarationRule).firstOrNull?.message;
    final missing = [for (var i = 0; i < _n; i++) if (_sampleValues[i].isEmpty) '{{${i + 1}}}'];
    return {
      'name': nameErr,
      'language': Validators.required(language, 'a language'),
      'body': bodyErr,
      'samples': missing.isEmpty ? null : 'Give an example for ${missing.join(', ')} — WhatsApp reviews the template with them.',
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _body.removeListener(_syncSamples);
    _form.dispose();
    for (final c in [_name, _language, _body, ..._samples]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.declareWhatsAppAccountTemplate(
        widget.account.accountKey,
        WhatsAppTemplateDeclaration(
          name: _name.text.trim(),
          language: _language.text.trim(),
          category: _category,
          body: _body.text,
          bodySamples: _sampleValues,
        ),
      );
      ref.invalidate(whatsAppAccountTemplatesProvider(widget.account.accountKey));
      ref.invalidate(whatsAppAccountsProvider);
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Created ${saved.name}. Submit it for review, then share it.');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(templateFieldError(e, _fieldByCode));
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final n = _n;
    final a = widget.account;
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: 'New template',
          subtitle: 'On the ${a.isPlatform ? 'platform account' : 'account of workspace ${a.tenantId}'}. Saved as a '
              'draft no workspace can send yet — submit it for review, then share it. Fields marked * are required.',
          body: EditorWithPreview(
            form: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: 'creating this template'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Template name',
                wireKey: 'name',
                controller: _name,
                form: _form,
                name: 'name',
                required: true,
                autofocus: true,
                mono: true,
                hint: 'e.g. appt_reminder_v1',
                helper: 'Unique on the account. Lowercase letters, digits and _; end with _v1 so a later wording can be _v2.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Language',
                wireKey: 'language',
                controller: _language,
                form: _form,
                name: 'language',
                required: true,
                mono: true,
                hint: 'e.g. es or en_US',
                helper: 'The language WhatsApp reviews the wording in.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<String>(
                label: 'Category',
                wireKey: 'category',
                form: _form,
                name: 'category',
                required: true,
                value: _category,
                options: templateCategoryOptions,
                helper: 'WhatsApp checks the wording fits the category and prices messages by it.',
                onChanged: (v) {
                  setState(() => _category = v);
                  _form.refresh();
                },
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Message',
                wireKey: 'body',
                controller: _body,
                form: _form,
                name: 'body',
                required: true,
                maxLines: 10,
                minLines: 5,
                hint: 'e.g. Hola {{1}}, tu cita es mañana a las {{2}}.',
                helper: 'Up to 1024 characters. Number the variables {{1}}, {{2}}, … in order; start and end with words.',
              ),
              if (n > 0) ...[
                const SizedBox(height: Gap.fieldGap),
                YFieldShell(
                  label: 'Example values',
                  wireKey: 'samples.body',
                  form: _form,
                  name: 'samples',
                  required: true,
                  helper: 'One realistic example per variable. WhatsApp reviews the template with them; they are never sent.',
                  child: Column(children: [
                    for (var i = 0; i < n; i++)
                      Padding(
                        padding: EdgeInsets.only(top: i == 0 ? 0 : 8),
                        child: TextField(
                          key: ValueKey('sample-${i + 1}'),
                          controller: _samples[i],
                          style: TextStyle(fontSize: 14, color: scheme.onSurface),
                          onChanged: (_) => _form.changed('samples'),
                          decoration: InputDecoration(
                            prefixIcon: Padding(
                              padding: const EdgeInsets.only(left: 12, right: 8),
                              child: Text('{{${i + 1}}}', style: monoStyle(context, size: 13, color: scheme.onSurfaceVariant)),
                            ),
                            prefixIconConstraints: const BoxConstraints(minWidth: 0, minHeight: 0),
                            hintText: i == 0 ? 'e.g. Ana' : null,
                          ),
                        ),
                      ),
                  ]),
                ),
              ],
              const SizedBox(height: Gap.lg),
              Text(
                'Templates with a document header or buttons are declared by the integration that sends them; '
                'they appear here once declared.',
                style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
              ),
            ],
            preview: WhatsAppBodyPreview(
              body: _body.text,
              samples: _sampleValues,
              issues: placeholderIssues(_body.text),
              declarationRulesBlock: true,
              footer: 'Shown with your example values; each send fills in its own.',
            ),
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: 'Create template',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
