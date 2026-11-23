import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/whatsapp_sender.dart';
import '../../../models/admin/whatsapp_template.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../../auth/application/auth_controller.dart';
import '../../auth/models/auth_state.dart';
import '../shared/admin_shared.dart';
import '../whatsapp_senders/whatsapp_senders_screen.dart' show whatsAppSendersProvider;
import 'template_list_kit.dart';

final whatsAppTemplatesProvider =
    FutureProvider.family<List<WhatsAppTemplateResponse>, ({String tenant, String sender})>((ref, key) async {
      liveResource(ref, AdminResourceKind.whatsappTemplate);
      final api = await ref.watch(adminApiProvider.future);
      final rows = await api.listWhatsAppTemplates(key.tenant, key.sender);
      // WhatsApp's verdict arrives by callback or the server's periodic sync,
      // neither of which the change feed sees.
      pollWhile(ref, anyPendingReview(rows), every: const Duration(seconds: 20));
      return rows;
    });

const _routePath = '/admin/whatsapp-templates';

Map<String, String> _routeParams(BuildContext context) =>
    GoRouter.maybeOf(context) == null ? const {} : GoRouterState.of(context).uri.queryParameters;

class WhatsAppTemplatesScreen extends ConsumerStatefulWidget {
  const WhatsAppTemplatesScreen({super.key});

  @override
  ConsumerState<WhatsAppTemplatesScreen> createState() => _WhatsAppTemplatesScreenState();
}

class _WhatsAppTemplatesScreenState extends ConsumerState<WhatsAppTemplatesScreen> {
  String? _senderId;
  WhatsAppSenderResponse? _sender;
  bool _showHidden = false;
  final Set<int> _selected = {};
  String? _handledLink;

  bool get _platformAdmin {
    final auth = ref.read(authProvider);
    return auth is AuthAuthenticated && auth.operator.platformAdmin;
  }

  // Hiding marks the template itself, so one other workspaces use is a
  // platform admin's to hide — as its wording is theirs to change.
  bool _canHide(WhatsAppTemplateResponse r) => !r.shared || _platformAdmin;
  bool _canEdit(WhatsAppTemplateResponse r) => r.isEditable && (!r.shared || _platformAdmin);

  ({String tenant, String sender})? get _key {
    final tenant = ref.read(currentTenantOrNull);
    final sender = _sender?.id;
    return tenant == null || sender == null ? null : (tenant: tenant, sender: sender);
  }

  void _refresh() {
    final key = _key;
    if (key != null) ref.invalidate(whatsAppTemplatesProvider(key));
  }

  Future<void> _openEditor([WhatsAppTemplateResponse? existing]) async {
    final key = _key;
    final sender = _sender;
    if (key == null || sender == null) return;
    await openTemplateSheet<void>(
      context,
      width: 960,
      label: existing == null ? 'New WhatsApp template' : 'Edit WhatsApp template',
      builder: (_) => WhatsAppTemplateEditorSheet(tenant: key.tenant, sender: sender, existing: existing),
    );
  }

  Future<void> _openDetail(WhatsAppTemplateResponse row) async {
    final key = _key;
    if (key == null) return;
    await openTemplateSheet<void>(
      context,
      width: 720,
      label: 'WhatsApp template',
      builder: (sheetContext) => _TemplateDetailSheet(
        keyOf: key,
        id: row.id,
        canEdit: _canEdit,
        canHide: _canHide,
        onEdit: (r) {
          Navigator.of(sheetContext).pop();
          _openEditor(r);
        },
        onSubmit: (r) => _submit(sheetContext, r),
        onHide: (r) => _setHidden(sheetContext, [r], hidden: !r.hidden),
        onDelete: (r) async {
          if (await _delete(sheetContext, r) && sheetContext.mounted) Navigator.of(sheetContext).pop();
        },
      ),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the sheet once the list is in.
  void _handleDeepLink(List<WhatsAppTemplateResponse> list) {
    final params = _routeParams(context);
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((r) => '${r.id}' == link || r.name == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No template “$link” on this sender', error: true);
      } else if (match != null) {
        await _openDetail(match);
      } else {
        await _openEditor();
      }
      if (mounted) GoRouter.maybeOf(context)?.go(_routePath);
      _handledLink = null;
    });
  }

  Future<void> _submit(BuildContext context, WhatsAppTemplateResponse row) async {
    final key = _key;
    if (key == null) return;
    final ok = await confirmAction(
      context,
      title: 'Submit ${row.name} for review?',
      message:
          'WhatsApp reviews each template before it can be used to message customers. '
          'This typically takes 1–3 business days. You can’t edit the wording while it’s under review.',
      confirmLabel: 'Submit for review',
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.submitWhatsAppTemplate(key.tenant, key.sender, row.id);
      _refresh();
      if (context.mounted) showToast(context, 'Submitted ${row.name}. This list updates when WhatsApp answers.');
    } catch (e) {
      if (context.mounted) showToast(context, formatError(e), error: true);
    }
  }

  Future<bool> _delete(BuildContext context, WhatsAppTemplateResponse row) async {
    final key = _key;
    if (key == null) return false;
    final ok = await confirmAction(
      context,
      destructive: true,
      title: row.staysInAccount ? 'Stop using ${row.name}?' : 'Remove ${row.name}?',
      message: row.staysInAccount
          ? 'This workspace will no longer be able to send ${row.name} (${row.locale}). The template itself stays '
                'on the WhatsApp account${row.shared ? ' for the other workspaces that use it' : ''}; '
                'a platform admin can share it with this workspace again.'
          : '${row.name} (${row.locale}) will be removed. Messages that name it fall back to the chat.',
      confirmLabel: row.staysInAccount ? 'Stop using' : 'Remove',
    );
    if (!ok) return false;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteWhatsAppTemplate(key.tenant, key.sender, row.id);
      _refresh();
      if (context.mounted) {
        showToast(context, row.staysInAccount ? 'This workspace no longer uses ${row.name}' : 'Removed ${row.name}');
      }
      return true;
    } catch (e) {
      if (context.mounted) showToast(context, formatError(e), error: true);
      return false;
    }
  }

  Future<void> _setHidden(
    BuildContext context,
    List<WhatsAppTemplateResponse> rows, {
    required bool hidden,
    bool undoable = true,
  }) async {
    final key = _key;
    if (key == null || rows.isEmpty) return;
    final ids = rows.map((r) => r.id).toList();
    final what = rows.length == 1 ? rows.single.name : '${rows.length} templates';
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.setWhatsAppTemplatesHidden(key.tenant, key.sender, ids, hidden: hidden);
      _refresh();
      if (mounted) setState(() => _selected.removeAll(ids));
      if (!context.mounted) return;
      showToast(
        context,
        hidden ? 'Hid $what. «Show hidden» brings ${rows.length == 1 ? 'it' : 'them'} back.' : '$what back in the list',
        actionLabel: undoable ? 'Undo' : null,
        onAction: undoable ? () => _setHidden(this.context, rows, hidden: !hidden, undoable: false) : null,
      );
    } catch (e) {
      if (context.mounted) showToast(context, formatError(e), error: true);
    }
  }

  Future<void> _hideOlder(List<WhatsAppTemplateResponse> rows, Map<int, String> newer) async {
    if (!await confirmHideOlderVersions(context, rows, newer)) return;
    if (mounted) await _setHidden(context, rows, hidden: true);
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'WhatsApp templates');
    }
    final senders = ref.watch(whatsAppSendersProvider(tenant));
    final eligible = (senders.valueOrNull ?? const <WhatsAppSenderResponse>[])
        .where((s) => s.status != 'REVOKED')
        .toList();
    _sender = eligible.where((s) => s.id == _senderId).firstOrNull ?? eligible.firstOrNull;

    return YPage(
      title: 'WhatsApp templates',
      subtitle:
          'Pre-approved messages for reaching customers outside the 24-hour service window. WhatsApp reviews each '
          'template once per WhatsApp account; a platform admin can share an approved one with other workspaces.',
      actions: [
        FilledButton.icon(
          onPressed: _sender == null ? null : () => _openEditor(),
          icon: const Icon(Icons.add, size: 18),
          label: const Text('New template'),
        ),
      ],
      child: LiveAsyncView<List<WhatsAppSenderResponse>>(
        value: senders,
        what: 'WhatsApp senders',
        onRetry: () => ref.invalidate(whatsAppSendersProvider(tenant)),
        data: (context, _) {
          final sender = _sender;
          if (sender == null) {
            return YEmptyState(
              icon: Icons.phone_iphone_outlined,
              title: 'No WhatsApp sender yet',
              message: 'Templates live on the WhatsApp account a sender uses. Register a sender first.',
              action: FilledButton.icon(
                onPressed: () => GoRouter.maybeOf(context)?.go('/admin/whatsapp-senders'),
                icon: const Icon(Icons.arrow_forward, size: 18),
                label: const Text('Go to WhatsApp senders'),
              ),
            );
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 460),
                  child: YSelectField<String>(
                    label: 'Sender',
                    wireKey: 'senderId',
                    helper: 'Templates belong to the WhatsApp account this number sends from.',
                    value: sender.id,
                    options: [for (final s in eligible) YOption(s.id, s.displayName, meta: s.phoneE164)],
                    onChanged: (v) => setState(() {
                      _senderId = v;
                      _selected.clear();
                    }),
                  ),
                ),
              ),
              const SizedBox(height: Gap.lg),
              Expanded(child: _list(tenant, sender)),
            ],
          );
        },
      ),
    );
  }

  Widget _list(String tenant, WhatsAppSenderResponse sender) {
    ref.watch(authProvider);
    final key = (tenant: tenant, sender: sender.id);
    final templates = ref.watch(whatsAppTemplatesProvider(key));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.whatsappTemplate));
    return LiveAsyncView<List<WhatsAppTemplateResponse>>(
      value: templates,
      what: 'templates',
      onRetry: () => ref.invalidate(whatsAppTemplatesProvider(key)),
      data: (context, all) {
        _handleDeepLink(all);
        final newer = newerVersions(all);
        final listed = listedTemplates(all, showHidden: _showHidden);
        final older = all.where((r) => !r.hidden && newer.containsKey(r.id) && _canHide(r)).toList()
          ..sort(compareTemplates);
        final selectable = listed.where(_canHide).map((r) => r.id).toSet();
        final selected = listed.where((r) => _selected.contains(r.id) && selectable.contains(r.id)).toList();
        final hiddenCount = all.where((r) => r.hidden).length;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
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
              ),
              const SizedBox(height: Gap.md),
            ],
            Expanded(
              child: TemplateTable<WhatsAppTemplateResponse>(
                rows: listed,
                newer: newer,
                selected: _selected,
                canSelect: _canHide,
                notSelectableReason: 'Other workspaces use this template too; a platform admin can hide it.',
                onSelected: (r, on) => setState(() => on ? _selected.add(r.id) : _selected.remove(r.id)),
                highlightIds: fresh,
                searchTextOf: (r) => '${r.name} ${r.locale} ${r.category} ${r.body}',
                subtitleOf: (r) {
                  final n = r.placeholderCount;
                  return '${templateCategoryLabel(r.category)} · $n variable${n == 1 ? '' : 's'}'
                      '${r.approvedAt != null ? ' · approved ${formatRelativeIso(r.approvedAt)}' : ''}';
                },
                tagsOf: (r) => [
                  if (r.shared)
                    const Tooltip(
                      message: 'Other workspaces on the same WhatsApp account use this template too.',
                      child: YBadge('Shared', tone: YTone.info, icon: Icons.groups_outlined),
                    ),
                ],
                primaryActionOf: (r) => r.canSubmit
                    ? FilledButton.tonal(onPressed: () => _submit(context, r), child: const Text('Submit for review'))
                    : null,
                menuOf: (r) => [
                  if (_canEdit(r)) TemplateAction('Edit', Icons.edit_outlined, () => _openEditor(r)),
                  if (_canHide(r))
                    TemplateAction(
                      r.hidden ? 'Unhide' : 'Hide',
                      r.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                      () => _setHidden(context, [r], hidden: !r.hidden),
                    ),
                  TemplateAction(
                    r.staysInAccount ? 'Stop using' : 'Remove',
                    Icons.delete_outline,
                    () => _delete(context, r),
                    destructive: true,
                  ),
                ],
                onRowTap: _openDetail,
                toolbarTrailing: [
                  if (hiddenCount > 0 || _showHidden)
                    ShowHiddenChip(
                      count: hiddenCount,
                      on: _showHidden,
                      onChanged: (v) => setState(() => _showHidden = v),
                    ),
                  SelectAllButton(
                    selectable: selectable,
                    selected: _selected,
                    onSelectAll: () => setState(() => _selected.addAll(selectable)),
                    onClear: () => setState(_selected.clear),
                  ),
                ],
                empty: all.isEmpty
                    ? templatesEmptyState(
                        message:
                            'A template is a message WhatsApp approved in advance, so the agent can write first — '
                            'a reminder, an alert — after the 24-hour window has closed.',
                        action: FilledButton.icon(
                          onPressed: () => _openEditor(),
                          icon: const Icon(Icons.add, size: 18),
                          label: const Text('Create your first template'),
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
          ],
        );
      },
    );
  }
}

/// One template, read-only, with what can be done to it.
class _TemplateDetailSheet extends ConsumerWidget {
  const _TemplateDetailSheet({
    required this.keyOf,
    required this.id,
    required this.canEdit,
    required this.canHide,
    required this.onEdit,
    required this.onSubmit,
    required this.onHide,
    required this.onDelete,
  });

  final ({String tenant, String sender}) keyOf;
  final int id;
  final bool Function(WhatsAppTemplateResponse) canEdit;
  final bool Function(WhatsAppTemplateResponse) canHide;
  final ValueChanged<WhatsAppTemplateResponse> onEdit;
  final ValueChanged<WhatsAppTemplateResponse> onSubmit;
  final ValueChanged<WhatsAppTemplateResponse> onHide;
  final ValueChanged<WhatsAppTemplateResponse> onDelete;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final all = ref.watch(whatsAppTemplatesProvider(keyOf)).valueOrNull ?? const <WhatsAppTemplateResponse>[];
    final row = all.where((r) => r.id == id).firstOrNull;
    if (row == null) {
      return YSheetScaffold(
        title: 'Template not found',
        body: const YEmptyState(
          icon: Icons.delete_outline,
          title: 'This template is gone',
          message: 'It was removed, or this workspace no longer uses it.',
        ),
      );
    }
    final info = TemplateStatusInfo.of(row.status);
    final newer = newerVersions(all)[row.id];
    final n = row.placeholderCount;
    return YSheetScaffold(
      title: row.name,
      subtitle:
          '${row.locale} · ${templateCategoryLabel(row.category)}${row.shared ? ' · shared with other workspaces' : ''}',
      body: ListView(
        padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
        children: [
          Row(
            children: [
              TemplateStatusBadge(status: row.status),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  info.description,
                  style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
                ),
              ),
            ],
          ),
          if (row.status == 'REJECTED' && (row.rejectionReason ?? '').isNotEmpty) ...[
            const SizedBox(height: Gap.md),
            YBanner(tone: YBannerTone.error, title: 'WhatsApp’s reason:', message: row.rejectionReason!),
          ],
          if (newer != null) ...[
            const SizedBox(height: Gap.md),
            YBanner(tone: YBannerTone.info, message: 'A newer version, $newer, replaces this one.'),
          ],
          if (row.shared && !canEdit(row) && row.isEditable) ...[
            const SizedBox(height: Gap.md),
            const YBanner(
              tone: YBannerTone.info,
              message: 'Other workspaces send this template too, so only a platform admin can change its wording.',
            ),
          ],
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
          TemplateFact('Template ID', '${row.id}', mono: true),
          if (row.contentSid != null) TemplateFact('Content SID', row.contentSid!, mono: true),
        ],
      ),
      footer: TemplateSheetFooter(
        children: [
          DangerButton(label: row.staysInAccount ? 'Stop using' : 'Remove', onPressed: () => onDelete(row)),
          if (canHide(row))
            OutlinedButton.icon(
              onPressed: () => onHide(row),
              icon: Icon(row.hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 18),
              label: Text(row.hidden ? 'Unhide' : 'Hide'),
            ),
          if (canEdit(row))
            OutlinedButton.icon(
              onPressed: () => onEdit(row),
              icon: const Icon(Icons.edit_outlined, size: 18),
              label: const Text('Edit'),
            ),
          if (row.canSubmit) FilledButton(onPressed: () => onSubmit(row), child: const Text('Submit for review')),
        ],
      ),
    );
  }
}

const _labels = {'name': 'Template name', 'locale': 'Locale', 'category': 'Category', 'body': 'Message'};

const _fieldByCode = {
  'bad_name': 'name',
  'template_exists': 'name',
  'bad_locale': 'locale',
  'bad_category': 'category',
  'bad_body': 'body',
  'not_editable': 'body',
};

/// Create a draft template on a sender, or change a draft's wording.
class WhatsAppTemplateEditorSheet extends ConsumerStatefulWidget {
  const WhatsAppTemplateEditorSheet({super.key, required this.tenant, required this.sender, this.existing});
  final String tenant;
  final WhatsAppSenderResponse sender;
  final WhatsAppTemplateResponse? existing;

  @override
  ConsumerState<WhatsAppTemplateEditorSheet> createState() => _WhatsAppTemplateEditorSheetState();
}

class _WhatsAppTemplateEditorSheetState extends ConsumerState<WhatsAppTemplateEditorSheet> {
  final _name = TextEditingController();
  final _locale = TextEditingController();
  final _body = TextEditingController();
  String _category = 'UTILITY';
  late final YFormController _form;

  WhatsAppTemplateResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _name.text = _base?.name ?? '';
    _locale.text = _base?.locale ?? widget.sender.defaultLocale;
    _body.text = _base?.body ?? '';
    _category = _base?.category ?? 'UTILITY';
    _form = YFormController(
      snapshot: () => {
        'name': _name.text.trim(),
        'locale': _locale.text.trim(),
        'category': _category,
        'body': _body.text,
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.whatsappTemplate.${widget.tenant}.${widget.sender.id}.${_base?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _body.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      if (!_editing) {
        _name.text = (v['name'] as String?) ?? _name.text;
        _locale.text = (v['locale'] as String?) ?? _locale.text;
      }
      _body.text = (v['body'] as String?) ?? _body.text;
      final cat = v['category'];
      if (cat is String && kTemplateCategories.contains(cat)) _category = cat;
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  Map<String, String?> _validate() {
    String? nameErr;
    if (!_editing) {
      nameErr = Validators.required(_name.text, 'a template name');
      final existing =
          ref.read(whatsAppTemplatesProvider((tenant: widget.tenant, sender: widget.sender.id))).valueOrNull ??
          const <WhatsAppTemplateResponse>[];
      if (nameErr == null && existing.any((r) => r.name == _name.text.trim() && r.locale == _locale.text.trim())) {
        nameErr =
            'This sender already has ${_name.text.trim()} in ${_locale.text.trim()}. Use a new name, e.g. ${TemplateVersion.of(_name.text.trim()).family}_v${TemplateVersion.of(_name.text.trim()).number + 1}.';
      }
    }
    final body = _body.text;
    String? bodyErr = Validators.required(body, 'the message wording');
    if (bodyErr == null && body.length > 1024) {
      bodyErr = 'Keep the message to 1024 characters (it has ${body.length}).';
    }
    return {
      'name': nameErr,
      'locale': _editing ? null : Validators.required(_locale.text, 'a locale'),
      'body': bodyErr,
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _body.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_name, _locale, _body]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final WhatsAppTemplateResponse saved;
      if (_editing) {
        saved = await api.updateWhatsAppTemplate(
          widget.tenant,
          widget.sender.id,
          _base!.id,
          WhatsAppTemplateUpdate(body: _body.text, category: _category),
        );
      } else {
        saved = await api.createWhatsAppTemplate(
          widget.tenant,
          widget.sender.id,
          WhatsAppTemplateRequest(
            name: _name.text.trim(),
            locale: _locale.text.trim(),
            category: _category,
            body: _body.text,
          ),
        );
      }
      ref.invalidate(whatsAppTemplatesProvider((tenant: widget.tenant, sender: widget.sender.id)));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(
        context,
        _editing ? 'Saved ${saved.name}' : 'Created ${saved.name}. Submit it for review when it reads right.',
      );
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(templateFieldError(e, _fieldByCode));
    }
  }

  @override
  Widget build(BuildContext context) {
    final rejected = _base?.status == 'REJECTED';
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? 'Edit ${_base!.name}' : 'New WhatsApp template',
          subtitle: _editing
              ? (rejected
                    ? 'Saving turns it back into a draft; submit it again for WhatsApp to review the new wording.'
                    : 'A draft — WhatsApp hasn’t seen it yet. Name and locale are fixed.')
              : 'On ${widget.sender.displayName} (${widget.sender.phoneE164}). Saved as a draft; you submit it for review next. Fields marked * are required.',
          body: EditorWithPreview(
            form: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this template'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Template name',
                wireKey: 'name',
                controller: _name,
                form: _form,
                name: 'name',
                required: !_editing,
                readOnly: _editing,
                autofocus: !_editing,
                mono: true,
                hint: 'e.g. appt_reminder_v1',
                helper: _editing
                    ? 'Fixed — integrations send the template by this name.'
                    : 'Integrations send the template by this name. Lowercase letters, digits and _ read best; end with _v1 so a later wording can be _v2.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Locale',
                wireKey: 'locale',
                controller: _locale,
                form: _form,
                name: 'locale',
                required: !_editing,
                readOnly: _editing,
                mono: true,
                hint: 'e.g. es-DO',
                helper: 'The language of the wording (BCP-47). One name can have one template per locale.',
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
                autofocus: _editing,
                maxLines: 10,
                minLines: 5,
                hint: 'e.g. Hola {{1}}, tu cita es mañana a las {{2}}.',
                helper: 'Up to 1024 characters. {{1}}, {{2}}, … are filled in when the message is sent.',
              ),
            ],
            preview: WhatsAppBodyPreview(
              body: _body.text,
              issues: placeholderIssues(_body.text),
              footer: 'Variables show as markers; the sending integration fills them in.',
            ),
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save changes' : 'Create template',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
