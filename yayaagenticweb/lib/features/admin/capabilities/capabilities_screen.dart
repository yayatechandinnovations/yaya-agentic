import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/conflict_banner.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/capability.dart';
import '../../../models/admin/tool.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../profiles/profiles_screen.dart' show capabilitiesProvider, profilesProvider;
import '../shared/admin_shared.dart';

final toolsProvider = FutureProvider<List<ToolResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.tool);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  return api.listTools(tenant: tenant);
});

/// Latest version of each capability plus its history.
class CapabilitySummary {
  CapabilitySummary(this.latest, this.versions);
  final CapabilityResponse latest;
  final List<CapabilityResponse> versions;
}

final capabilitySummariesProvider = Provider<AsyncValue<List<CapabilitySummary>>>((ref) {
  return ref.watch(capabilitiesProvider).whenData((all) {
    final byId = <String, List<CapabilityResponse>>{};
    for (final c in all) {
      byId.putIfAbsent(c.id, () => []).add(c);
    }
    return [
      for (final e in byId.entries)
        CapabilitySummary((e.value..sort((a, b) => b.version.compareTo(a.version))).first, e.value),
    ]..sort((a, b) => a.latest.id.compareTo(b.latest.id));
  });
});

/// Capability id → ids of the profiles (latest versions) that enable it.
final capabilityUsageProvider = Provider<Map<String, List<String>>>((ref) {
  final profiles = ref.watch(profilesProvider).valueOrNull ?? const [];
  final latest = <String, ({int version, List<String> caps})>{};
  for (final p in profiles) {
    final cur = latest[p.id];
    if (cur == null || p.version > cur.version) latest[p.id] = (version: p.version, caps: p.capabilities);
  }
  final out = <String, List<String>>{};
  latest.forEach((profileId, v) {
    for (final c in v.caps) {
      out.putIfAbsent(c, () => []).add(profileId);
    }
  });
  return out;
});

const _labels = {
  'id': 'Capability ID',
  'label': 'Label',
  'description': 'Description',
  'llmGuidance': 'Guidance for the model',
  'tools': 'Tools',
  'followUpHints': 'Follow-up suggestions',
};

class CapabilitiesScreen extends ConsumerStatefulWidget {
  const CapabilitiesScreen({super.key});
  @override
  ConsumerState<CapabilitiesScreen> createState() => _CapabilitiesScreenState();
}

class _CapabilitiesScreenState extends ConsumerState<CapabilitiesScreen> {
  String? _handledLink;

  Future<void> _open(CapabilitySummary? existing) async {
    await showYSheet<void>(
      context,
      label: existing == null ? 'New capability' : 'Edit capability',
      builder: (_) => CapabilityEditorSheet(existing: existing),
    );
  }

  /// `?id=` / `?new=1` (⌘K, cross-links) open the editor once the list is in.
  void _handleDeepLink(List<CapabilitySummary> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((s) => s.latest.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No capability “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/capabilities');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(currentTenantOrNull) == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Capabilities');
    }
    final summaries = ref.watch(capabilitySummariesProvider);
    final usage = ref.watch(capabilityUsageProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.capability));
    summaries.whenData(_handleDeepLink);

    return YPage(
      title: 'Capabilities',
      subtitle: 'What a profile can do for the user. Each capability bundles tools and guidance, and can surface as a quick-reply.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New capability')),
      ],
      child: LiveAsyncView<List<CapabilitySummary>>(
        value: summaries,
        what: 'capabilities',
        onRetry: () => ref.invalidate(capabilitiesProvider),
        data: (context, list) => YTable<CapabilitySummary>(
          rows: list,
          noun: 'capabilities',
          idOf: (s) => s.latest.id,
          searchHint: 'Search by ID, label or tool',
          searchTextOf: (s) => '${s.latest.id} ${s.latest.label} ${s.latest.description ?? ''} ${s.latest.tools.join(' ')}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('No tools', (s) => s.latest.tools.isEmpty),
            YFilter('Unused', (s) => (usage[s.latest.id] ?? const []).isEmpty),
          ],
          empty: YEmptyState(
            icon: Icons.checklist_outlined,
            title: 'No capabilities yet',
            message: 'A capability is something a profile can do — “Track an order”, “Check out” — backed by one or more tools.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Create your first capability')),
          ),
          columns: [
            YColumn(
              label: 'Capability',
              flex: 3,
              sortKey: (s) => s.latest.id,
              cell: (context, s) => YCellStack(primary: s.latest.label, secondary: s.latest.id),
            ),
            YColumn(
              label: 'Tools',
              flex: 3,
              sortKey: (s) => s.latest.tools.length,
              cell: (context, s) => s.latest.tools.isEmpty
                  ? YBadge('No tools', tone: YTone.warning)
                  : Text(s.latest.tools.join(', '), overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
            ),
            YColumn(
              label: 'Version',
              width: 110,
              sortKey: (s) => s.latest.version,
              cell: (context, s) => YCellStack(
                primary: 'v${s.latest.version}',
                mono: true,
                secondary: s.versions.length == 1 ? null : '${s.versions.length} versions',
              ),
            ),
            YColumn(
              label: 'Used by',
              flex: 2,
              sortKey: (s) => (usage[s.latest.id] ?? const []).length,
              cell: (context, s) {
                final p = usage[s.latest.id] ?? const [];
                return Text(
                  p.isEmpty ? 'No profile yet' : p.length == 1 ? p.first : '${p.length} profiles',
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: p.isEmpty ? Theme.of(context).colorScheme.onSurfaceVariant : null),
                );
              },
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.latest.createdAt ?? '',
              cell: (context, s) => Text(fresh.contains(s.latest.id) ? 'Just now' : formatRelativeIso(s.latest.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Create or edit (publish a new version of) one capability, in a side sheet.
class CapabilityEditorSheet extends ConsumerStatefulWidget {
  const CapabilityEditorSheet({super.key, this.existing});
  final CapabilitySummary? existing;

  @override
  ConsumerState<CapabilityEditorSheet> createState() => _CapabilityEditorSheetState();
}

class _CapabilityEditorSheetState extends ConsumerState<CapabilityEditorSheet> {
  final _id = TextEditingController();
  final _label = TextEditingController();
  final _description = TextEditingController();
  final _guidance = TextEditingController();
  final _hints = TextEditingController();
  List<String> _tools = [];
  late final YFormController _form;
  CapabilityResponse? _base;

  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _base = widget.existing?.latest;
    _load(_base);
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'id': _id.text.trim(),
        'label': _label.text.trim(),
        'description': _description.text.trim(),
        'llmGuidance': _guidance.text.trim(),
        'tools': _tools,
        'followUpHints': _hintList(),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.capability.$tenant.${_base?.id ?? 'new'}',
      baseVersion: _base?.version,
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _applySnapshot(d.values);
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _load(CapabilityResponse? c) {
    _id.text = c?.id ?? '';
    _label.text = c?.label ?? '';
    _description.text = c?.description ?? '';
    _guidance.text = c?.llmGuidance ?? '';
    _hints.text = (c?.followUpHints ?? const []).join('\n');
    _tools = List.of(c?.tools ?? const []);
  }

  void _applySnapshot(Map<String, Object?> v) {
    if (!_editing) _id.text = (v['id'] as String?) ?? _id.text;
    _label.text = (v['label'] as String?) ?? _label.text;
    _description.text = (v['description'] as String?) ?? _description.text;
    _guidance.text = (v['llmGuidance'] as String?) ?? _guidance.text;
    final hints = v['followUpHints'];
    if (hints is List) _hints.text = hints.join('\n');
    final tools = v['tools'];
    if (tools is List) _tools = tools.map((e) => '$e').toList();
  }

  List<String> _hintList() => _hints.text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

  Map<String, String?> _validate() {
    final existing = ref.read(capabilitySummariesProvider).valueOrNull ?? const [];
    var idErr = _editing ? null : Validators.slugId(_id.text, 'a capability ID');
    if (idErr == null && !_editing && existing.any((s) => s.latest.id == _id.text.trim())) {
      idErr = 'A capability with this ID already exists. Open it to publish a new version.';
    }
    final known = {for (final t in ref.read(toolsProvider).valueOrNull ?? const <ToolResponse>[]) t.id};
    final missing = _tools.where((t) => known.isNotEmpty && !known.contains(t)).toList();
    return {
      'id': idErr,
      'label': Validators.required(_label.text, 'the label users see'),
      'tools': missing.isEmpty ? null : '${missing.join(', ')} ${missing.length == 1 ? 'doesn’t' : 'don’t'} exist on this tenant. Remove ${missing.length == 1 ? 'it' : 'them'}.',
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_id, _label, _description, _guidance, _hints]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.createCapability(
        CapabilityRequest(
          tenant: tenant,
          id: _id.text.trim(),
          label: _label.text.trim(),
          description: _description.text.trim().isEmpty ? null : _description.text.trim(),
          llmGuidance: _guidance.text.trim().isEmpty ? null : _guidance.text.trim(),
          tools: _tools,
          followUpHints: _hintList(),
          menu: _base?.menu,
        ),
        baseVersion: _form.baseVersion,
      );
      ref.invalidate(capabilitiesProvider);
      _form.saving = false;
      _form.markClean(baseVersion: saved.version);
      if (!mounted) return;
      showToast(context, _editing ? 'Published ${saved.id} v${saved.version}' : 'Created ${saved.id}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final tools = ref.watch(toolsProvider).valueOrNull ?? const <ToolResponse>[];
    final latestTools = <String, ToolResponse>{};
    for (final t in tools) {
      final cur = latestTools[t.id];
      if (cur == null || t.version > cur.version) latestTools[t.id] = t;
    }
    final usage = _base == null ? const <String>[] : (ref.watch(capabilityUsageProvider)[_base!.id] ?? const []);
    final latestVersion = _base == null
        ? null
        : ref.watch(capabilitySummariesProvider).valueOrNull?.where((s) => s.latest.id == _base!.id).firstOrNull?.latest.version;
    final next = (_form.baseVersion ?? 0) + 1;

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? _base!.label : 'New capability',
          subtitle: _editing
              ? '${_base!.id} · v${latestVersion ?? _base!.version} is live. Saving publishes v$next; running conversations keep their version.'
              : 'Fields marked * are required.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_editing)
                LiveConflictBanner(
                  kind: AdminResourceKind.capability,
                  id: _base!.id,
                  baseVersion: _form.baseVersion,
                  dirty: _form.isDirty,
                  noun: 'This capability',
                  onKeepMine: (v) => setState(() => _form.baseVersion = v),
                ),
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'publishing' : 'creating this capability'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Capability ID',
                wireKey: 'id',
                controller: _id,
                form: _form,
                name: 'id',
                required: !_editing,
                readOnly: _editing,
                autofocus: !_editing,
                mono: true,
                hint: 'e.g. track-order',
                helper: _editing ? 'Fixed — profiles refer to it by this ID.' : 'Profiles refer to it by this ID; it can’t change later.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Label',
                wireKey: 'label',
                controller: _label,
                form: _form,
                name: 'label',
                required: true,
                autofocus: _editing,
                hint: 'e.g. Track an order',
                helper: 'What users see on the quick-reply button.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Description',
                wireKey: 'description',
                controller: _description,
                form: _form,
                name: 'description',
                optional: true,
                maxLines: 3,
                minLines: 2,
                helper: 'One sentence users may see alongside the label.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Guidance for the model',
                wireKey: 'llmGuidance',
                controller: _guidance,
                form: _form,
                name: 'llmGuidance',
                optional: true,
                maxLines: 6,
                minLines: 3,
                hint: 'e.g. Ask for the order number before calling the lookup tool.',
                helper: 'When and how to use the tools below. Added to the prompt only for profiles that enable this capability.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YChipsField(
                label: 'Tools',
                wireKey: 'tools',
                form: _form,
                name: 'tools',
                optional: true,
                selected: _tools,
                resources: [
                  for (final t in latestTools.values)
                    YResource(
                      id: t.id,
                      meta: '${t.handler.kind == 'HTTP' ? 'HTTP ${t.handler.httpSpec?.method ?? ''}' : 'Bean'} · v${t.version}',
                    ),
                ],
                hint: 'Type to add a tool…',
                helper: 'Only these tools are offered to the model when a profile enables this capability.',
                onChanged: (v) => setState(() => _tools = v),
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Follow-up suggestions',
                wireKey: 'followUpHints',
                controller: _hints,
                form: _form,
                name: 'followUpHints',
                optional: true,
                maxLines: 5,
                minLines: 2,
                hint: 'Track another order\nTalk to a person',
                helper: 'One per line. Shown as quick-reply chips after this capability runs.',
              ),
              if (_editing) ...[
                const SizedBox(height: Gap.xl),
                Text('Used by', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
                const SizedBox(height: 6),
                if (usage.isEmpty)
                  Text('No profile enables this capability yet.', style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))
                else
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    for (final p in usage)
                      ActionChip(
                        label: Text(p, style: monoStyle(context, size: 12.5)),
                        onPressed: () async {
                          final router = GoRouter.of(context);
                          if (await UnsavedChanges.instance.confirmLeave(context)) {
                            _form.markClean();
                            if (context.mounted) Navigator.of(context).pop();
                            router.go('/admin/profiles/${Uri.encodeComponent(p)}');
                          }
                        },
                      ),
                  ]),
              ],
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Publish v$next' : 'Create capability',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
