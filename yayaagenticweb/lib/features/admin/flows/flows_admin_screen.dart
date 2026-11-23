import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/conflict_banner.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/start_flow.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';
import 'flow_builder.dart';
import 'flow_drafts.dart';
import 'flow_templates.dart';

/// Session Start Flow admin: author the ordered, data-bound tool steps that run
/// deterministically when a conversation starts — recognize / verify / register
/// / enrich, and shape the opening message. Built graphically (no JSON). Bind a
/// flow to a WhatsApp sender or chat entry point (their "Start flow" field), or
/// to a profile default. See `docs/design/session-start-flows.md`.

/// Every version of every flow on the tenant.
final startFlowsProvider =
    FutureProvider.family<List<StartFlowResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.startFlow);
  final api = await ref.watch(adminApiProvider.future);
  return api.listStartFlows(tenantId);
});

final _toolIdsProvider = FutureProvider.family<List<String>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.tool);
  final api = await ref.watch(adminApiProvider.future);
  final tools = await api.listTools(tenant: tenantId);
  return tools.map((t) => t.id).toSet().toList()..sort();
});

/// The latest version of a flow, its history, and the version that runs.
class FlowSummary {
  FlowSummary(this.latest, this.versions);
  final StartFlowResponse latest;
  final List<StartFlowResponse> versions;

  /// The newest ACTIVE version — what new conversations run — if any.
  StartFlowResponse? get live => versions.where((v) => v.isActive).firstOrNull;

  /// The latest version is a draft and something older is live.
  bool get hasUnpublished => latest.isDraft && live != null;
}

List<FlowSummary> summarizeFlows(List<StartFlowResponse> all) {
  final byId = <String, List<StartFlowResponse>>{};
  for (final f in all) {
    byId.putIfAbsent(f.id, () => []).add(f);
  }
  return [
    for (final e in byId.entries)
      FlowSummary((e.value..sort((a, b) => b.version.compareTo(a.version))).first, e.value),
  ]..sort((a, b) => a.latest.name.toLowerCase().compareTo(b.latest.name.toLowerCase()));
}

String _modeLabel(String mode) => switch (mode) {
      'LLM_COMPOSED' => 'Assistant writes it',
      'HYBRID' => 'Template + assistant',
      _ => 'Template',
    };

String _stepsSummary(StartFlowResponse f) {
  if (f.steps.isEmpty) return 'Greeting only';
  final kinds = <String, int>{};
  for (final s in f.steps) {
    final k = s is Map ? '${s['kind']}' : 'RunTool';
    kinds[k] = (kinds[k] ?? 0) + 1;
  }
  final words = {'RunTool': 'tool', 'Elicit': 'question', 'Decision': 'branch'};
  return kinds.entries.map((e) => '${e.value} ${words[e.key] ?? e.key}${e.value == 1 ? '' : (e.key == 'Decision' ? 'es' : 's')}').join(' · ');
}

YBadge _statusBadge(FlowSummary s) {
  final f = s.latest;
  if (f.isActive) return const YBadge('Live', tone: YTone.success, icon: Icons.circle);
  if (f.isArchived) return const YBadge('Archived', tone: YTone.neutral, icon: Icons.inventory_2_outlined);
  return const YBadge('Draft', tone: YTone.warning, icon: Icons.edit_note);
}

/// Asks before publishing, saying which conversations the new version reaches.
Future<bool> confirmPublishFlow(BuildContext context, {required String name, required String id, required int version, int? liveVersion}) =>
    confirmAction(
      context,
      title: 'Publish “$name” v$version?',
      message: 'New conversations on every sender, entry point and profile bound to $id start with v$version'
          '${liveVersion == null ? '' : ' instead of v$liveVersion'}. Conversations already running are not affected.',
      confirmLabel: 'Publish v$version',
    );

class StartFlowsAdminScreen extends ConsumerStatefulWidget {
  const StartFlowsAdminScreen({super.key});

  @override
  ConsumerState<StartFlowsAdminScreen> createState() => _StartFlowsAdminScreenState();
}

class _StartFlowsAdminScreenState extends ConsumerState<StartFlowsAdminScreen> {
  String? _handledLink;

  Future<void> _open(String tenant, FlowSummary? existing) async {
    await showYSheet<void>(
      context,
      width: 960,
      label: existing == null ? 'New start flow' : 'Edit start flow',
      builder: (_) => StartFlowEditorSheet(tenant: tenant, existing: existing),
    );
  }

  Future<void> _publish(String tenant, FlowSummary s) async {
    final ok = await confirmPublishFlow(context,
        name: s.latest.name, id: s.latest.id, version: s.latest.version, liveVersion: s.live?.version);
    if (!ok || !mounted) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final p = await api.publishStartFlow(tenant, s.latest.id);
      ref.invalidate(startFlowsProvider(tenant));
      if (mounted) showToast(context, 'Published ${p.id} v${p.version}');
    } catch (e) {
      if (mounted) showToast(context, 'Couldn’t publish ${s.latest.id}: ${formatError(e)}', error: true);
    }
  }

  /// `?id=` / `?new=1` open the editor once the list is in.
  void _handleDeepLink(String tenant, List<FlowSummary> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((s) => s.latest.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No start flow “$link” on this tenant', error: true);
      } else {
        await _open(tenant, match);
      }
      if (mounted) context.go('/admin/start-flows');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Start flows');
    }
    final summaries = ref.watch(startFlowsProvider(tenant)).whenData(summarizeFlows);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.startFlow));
    final scheme = Theme.of(context).colorScheme;
    summaries.whenData((l) => _handleDeepLink(tenant, l));

    return YPage(
      title: 'Start flows',
      subtitle: 'Automation that runs when a conversation starts — recognise, verify, enrich — and the first message it opens with. '
          'Bind a flow on a sender or chat entry point, or as a profile default.',
      actions: [
        FilledButton.icon(onPressed: () => _open(tenant, null), icon: const Icon(Icons.add, size: 18), label: const Text('New flow')),
      ],
      child: LiveAsyncView<List<FlowSummary>>(
        value: summaries,
        what: 'start flows',
        onRetry: () => ref.invalidate(startFlowsProvider(tenant)),
        data: (context, list) => YTable<FlowSummary>(
          rows: list,
          noun: 'flows',
          idOf: (s) => s.latest.id,
          searchHint: 'Search by name, ID or description',
          searchTextOf: (s) => '${s.latest.id} ${s.latest.name} ${s.latest.description ?? ''}',
          highlightIds: fresh,
          onRowTap: (s) => _open(tenant, s),
          filters: [
            YFilter('Live', (s) => s.latest.isActive),
            YFilter('Draft', (s) => s.latest.isDraft),
            YFilter('Unpublished changes', (s) => s.hasUnpublished),
            YFilter('Assistant greeting', (s) => s.latest.greetingMode != 'STATIC_TEMPLATE'),
          ],
          empty: YEmptyState(
            icon: Icons.play_circle_outline,
            title: 'No start flows yet',
            message: 'A start flow runs the moment a conversation opens — look the customer up, verify them, then greet them by name.',
            action: FilledButton.icon(onPressed: () => _open(tenant, null), icon: const Icon(Icons.add, size: 18), label: const Text('Create your first flow')),
          ),
          columns: [
            YColumn(
              label: 'Flow',
              flex: 3,
              sortKey: (s) => s.latest.name.toLowerCase(),
              cell: (context, s) => YCellStack(primary: s.latest.name.isEmpty ? s.latest.id : s.latest.name, secondary: s.latest.id),
            ),
            YColumn(
              label: 'Status',
              flex: 2,
              sortKey: (s) => s.latest.status,
              cell: (context, s) => Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
                _statusBadge(s),
                if (s.hasUnpublished)
                  Text('v${s.live!.version} is live', style: TextStyle(fontSize: 12.5, color: scheme.onSurfaceVariant)),
                if (s.latest.isDraft)
                  TextButton(onPressed: () => _publish(tenant, s), child: Text('Publish v${s.latest.version}')),
              ]),
            ),
            YColumn(
              label: 'Steps',
              flex: 2,
              sortKey: (s) => s.latest.stepCount,
              cell: (context, s) => Text(_stepsSummary(s.latest), overflow: TextOverflow.ellipsis),
            ),
            YColumn(
              label: 'First message',
              flex: 2,
              sortKey: (s) => s.latest.greetingMode,
              cell: (context, s) => Text(_modeLabel(s.latest.greetingMode), overflow: TextOverflow.ellipsis),
            ),
            YColumn(
              label: 'Version',
              width: 120,
              sortKey: (s) => s.latest.version,
              cell: (context, s) => YCellStack(
                primary: 'v${s.latest.version}',
                mono: true,
                secondary: s.versions.length == 1 ? null : '${s.versions.length} versions',
              ),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.latest.updatedAt ?? s.latest.createdAt ?? '',
              cell: (context, s) => Text(fresh.contains(s.latest.id) ? 'Just now' : formatRelativeIso(s.latest.updatedAt ?? s.latest.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Create a flow, or save its next version (a draft until published).
class StartFlowEditorSheet extends ConsumerStatefulWidget {
  const StartFlowEditorSheet({super.key, required this.tenant, this.existing});
  final String tenant;
  final FlowSummary? existing;

  @override
  ConsumerState<StartFlowEditorSheet> createState() => _StartFlowEditorSheetState();
}

class _StartFlowEditorSheetState extends ConsumerState<StartFlowEditorSheet> {
  late FlowDraft _draft;
  late final YFormController _form;
  StartFlowResponse? _base;

  /// Filled by [_validate] — the summary reads labels for dynamic step fields from here.
  final Map<String, String> _labels = {};

  /// Bumped when the draft is replaced wholesale (template, restored draft,
  /// reload) so every field re-reads its value.
  int _rev = 0;
  ({DateTime savedAt, Map<String, Object?> values})? _pendingDraft;

  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _base = widget.existing?.latest;
    _draft = _base != null ? FlowDraft.fromResponse(_base!) : FlowDraft();
    _form = YFormController(
      snapshot: () => _draft.toSnapshot(),
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.startFlow.${widget.tenant}.${_base?.id ?? 'new'}',
      baseVersion: _base?.version,
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d != null && mounted) setState(() => _pendingDraft = d);
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  Map<String, String?> _validate() {
    final existing = (ref.read(startFlowsProvider(widget.tenant)).valueOrNull ?? const []).map((f) => f.id).toSet();
    final tools = (ref.read(_toolIdsProvider(widget.tenant)).valueOrNull ?? const <String>[]).toSet();
    final r = validateFlow(_draft, editing: _editing, existingIds: existing, knownTools: tools);
    _labels
      ..clear()
      ..addAll(r.labels);
    return r.errors;
  }

  void _changed() {
    setState(() {});
    _form.refresh();
  }

  void _replaceDraft(FlowDraft d) {
    setState(() {
      _draft = d;
      if (_editing) _draft.id = _base!.id;
      _rev++;
    });
    _form.refresh();
  }

  Future<void> _useTemplate(String name) async {
    if (_form.isDirty) {
      final ok = await confirmAction(
        context,
        title: 'Replace your changes with “$name”?',
        message: 'The template replaces the steps and greeting you have so far.',
        confirmLabel: 'Use template',
        destructive: true,
      );
      if (!ok) return;
    }
    _replaceDraft(kFlowTemplates[name]!());
  }

  void _restoreDraft() {
    final d = _pendingDraft;
    if (d == null) return;
    _pendingDraft = null;
    _replaceDraft(FlowDraft.fromSnapshot(d.values));
  }

  void _discardDraft() {
    _form.clearDraft();
    setState(() => _pendingDraft = null);
  }

  void _reloadLatest(StartFlowResponse latest) {
    setState(() {
      _base = latest;
      _draft = FlowDraft.fromResponse(latest);
      _rev++;
    });
    _form.markClean(baseVersion: latest.version);
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    super.dispose();
  }

  Future<void> _save({required bool publish}) async {
    if (!_form.submit()) return;
    final next = _editing ? (_form.baseVersion ?? _base!.version) + 1 : 1;
    if (publish) {
      final live = widget.existing?.live;
      final ok = await confirmPublishFlow(context,
          name: _draft.name.trim(), id: _draft.id.trim(), version: next, liveVersion: live?.version);
      if (!ok || !mounted) return;
    }
    _form.saving = true;
    final api = await ref.read(adminApiProvider.future);
    StartFlowResponse saved;
    try {
      final body = _draft.toRequest(publish: publish && !_editing);
      saved = _editing ? await api.updateStartFlow(widget.tenant, _base!.id, body) : await api.createStartFlow(widget.tenant, body);
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
      return;
    }
    // An update always lands as a draft; publishing it is a second call, and
    // if that one fails the new version is still saved.
    String? publishError;
    if (publish && _editing) {
      try {
        saved = await api.publishStartFlow(widget.tenant, saved.id);
      } catch (e) {
        publishError = formatError(e);
      }
    }
    ref.invalidate(startFlowsProvider(widget.tenant));
    _form.saving = false;
    _form.markClean(baseVersion: saved.version);
    if (!mounted) return;
    if (publishError != null) {
      showToast(context, 'Saved ${saved.id} v${saved.version} as a draft, but publishing failed: $publishError', error: true);
    } else if (saved.isActive) {
      showToast(context, '${_editing ? 'Published' : 'Created and published'} ${saved.id} v${saved.version}');
    } else {
      showToast(context, _editing ? 'Saved ${saved.id} v${saved.version} as a draft' : 'Created ${saved.id} as a draft');
    }
    Navigator.of(context).pop();
  }

  Future<void> _publishCurrent(FlowSummary s) async {
    final ok = await confirmPublishFlow(context,
        name: s.latest.name, id: s.latest.id, version: s.latest.version, liveVersion: s.live?.version);
    if (!ok || !mounted) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final p = await api.publishStartFlow(widget.tenant, s.latest.id);
      ref.invalidate(startFlowsProvider(widget.tenant));
      if (mounted) showToast(context, 'Published ${p.id} v${p.version}');
    } catch (e) {
      if (mounted) showToast(context, 'Couldn’t publish ${s.latest.id}: ${formatError(e)}', error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final toolIds = ref.watch(_toolIdsProvider(widget.tenant)).valueOrNull ?? const <String>[];
    final all = ref.watch(startFlowsProvider(widget.tenant)).valueOrNull;
    final summary = _base == null || all == null
        ? null
        : summarizeFlows(all).where((s) => s.latest.id == _base!.id).firstOrNull;
    final latest = summary?.latest;
    final next = (_form.baseVersion ?? 0) + 1;
    final live = summary?.live ?? widget.existing?.live;
    final unresolved = latest != null && _form.baseVersion != null && latest.version > _form.baseVersion!;

    final String subtitle;
    if (!_editing) {
      subtitle = 'Steps run top to bottom when a conversation starts, then the first message is sent. Fields marked * are required.';
    } else {
      final cur = latest ?? _base!;
      subtitle = '${cur.id} · v${cur.version} is ${cur.isActive ? 'live' : cur.isDraft ? 'a draft' : 'archived'}'
          '${live != null && live.version != cur.version ? ' (v${live.version} is live)' : ''}. '
          'Saving creates v$next as a draft; conversations already running are not affected.';
    }

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: () => _save(publish: false),
        child: YSheetScaffold(
          title: _editing ? (_base!.name.isEmpty ? _base!.id : _base!.name) : 'New start flow',
          subtitle: subtitle,
          headerActions: [
            if (!_editing)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: MenuAnchor(
                  menuChildren: [
                    for (final name in kFlowTemplates.keys)
                      MenuItemButton(onPressed: () => _useTemplate(name), child: Text(name)),
                  ],
                  builder: (context, c, _) => OutlinedButton.icon(
                    onPressed: () => c.isOpen ? c.close() : c.open(),
                    icon: const Icon(Icons.auto_awesome_outlined, size: 18),
                    label: const Text('Start from a template'),
                  ),
                ),
              ),
            if (summary != null && summary.latest.isDraft && !_form.isDirty && !unresolved)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: OutlinedButton.icon(
                  onPressed: () => _publishCurrent(summary),
                  icon: const Icon(Icons.publish_outlined, size: 18),
                  label: Text('Publish v${summary.latest.version}'),
                ),
              ),
          ],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_pendingDraft != null) ...[
                YBanner(
                  tone: YBannerTone.info,
                  title: 'You have an unsaved draft',
                  message: 'from ${formatRelative(_pendingDraft!.savedAt).toLowerCase()} on this device.',
                  actions: [
                    FilledButton(onPressed: _restoreDraft, child: const Text('Restore draft')),
                    OutlinedButton(onPressed: _discardDraft, child: const Text('Discard it')),
                  ],
                ),
                const SizedBox(height: Gap.lg),
              ],
              if (_editing && latest != null)
                LiveConflictBanner(
                  kind: AdminResourceKind.startFlow,
                  id: _base!.id,
                  baseVersion: _form.baseVersion,
                  dirty: _form.isDirty,
                  noun: 'This flow',
                  onReview: _form.isDirty ? null : () => _reloadLatest(latest),
                  onKeepMine: (v) => setState(() => _form.baseVersion = v),
                ),
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this flow'),
                const SizedBox(height: Gap.lg),
              ],
              KeyedSubtree(
                key: ValueKey(_rev),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  YFormSection(
                    title: 'Basics',
                    children: [
                      FlowTextField(
                        label: 'Flow ID',
                        wireKey: 'id',
                        initial: _draft.id,
                        form: _form,
                        name: 'id',
                        required: !_editing,
                        readOnly: _editing,
                        autofocus: !_editing,
                        mono: true,
                        hint: 'e.g. returning-customer',
                        helper: _editing
                            ? 'Fixed — senders, entry points and profiles bind to the flow by this ID.'
                            : 'Letters, digits, underscores and dashes. Senders, entry points and profiles bind to it; it can’t change later.',
                        onChanged: (v) {
                          _draft.id = v.trim();
                          _changed();
                        },
                      ),
                      FlowTextField(
                        label: 'Name',
                        wireKey: 'name',
                        initial: _draft.name,
                        form: _form,
                        name: 'name',
                        required: true,
                        autofocus: _editing,
                        hint: 'e.g. Returning customer recognition',
                        onChanged: (v) {
                          _draft.name = v;
                          _changed();
                        },
                      ),
                      FlowTextField(
                        label: 'Description',
                        wireKey: 'description',
                        initial: _draft.description,
                        optional: true,
                        maxLines: 2,
                        helper: 'What the flow does, for whoever binds it next.',
                        onChanged: (v) {
                          _draft.description = v;
                          _changed();
                        },
                      ),
                    ],
                  ),
                  const SizedBox(height: Gap.lg),
                  YFormSection(
                    title: 'Steps',
                    description: 'Run automatically, top to bottom, before the user sees anything. Rules after a step can jump, finish or stop.',
                    children: [FlowStepsEditor(flow: _draft, toolIds: toolIds, form: _form, onChanged: _changed)],
                  ),
                  const SizedBox(height: Gap.lg),
                  YFormSection(
                    title: 'First message',
                    description: 'What the user sees when the flow finishes — or the fallback when it stops early.',
                    children: [GreetingEditor(greeting: _draft.greeting, flow: _draft, form: _form, onChanged: _changed)],
                  ),
                ]),
              ),
              const SizedBox(height: Gap.lg),
              Text('Time and step limits are kept as they are; new flows get the server defaults.',
                  style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant)),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save v$next as draft' : 'Create draft',
            onPrimary: () => _save(publish: false),
            onCancel: () => closeYSheet(context),
            secondary: [
              OutlinedButton(
                onPressed: _form.saving ? null : () => _save(publish: true),
                child: Text(_editing ? 'Save and publish v$next' : 'Create and publish'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
