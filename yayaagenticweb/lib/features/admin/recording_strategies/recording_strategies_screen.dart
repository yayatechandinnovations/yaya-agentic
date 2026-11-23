import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/profile.dart';
import '../../../models/admin/recording_strategy.dart';
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
import '../../../ui/form/y_json_field.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';

/// The latest strategy at every scope we know of: the tenant itself and each
/// of its profiles. There is no list-all endpoint, so this asks per scope —
/// a strategy left on a profile id that no longer exists won't show.
final recordingStrategiesProvider = FutureProvider<List<RecordingStrategyResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.recordingStrategy);
  final tenant = ref.watch(currentTenantOrNull);
  if (tenant == null) return const [];
  final api = await ref.watch(adminApiProvider.future);
  // A profile list that fails to load still leaves the tenant's own strategy worth showing.
  final profiles = await ref.watch(profilesProvider.future).catchError((Object _) => const <ProfileResponse>[]);
  final profileIds = {for (final p in profiles) p.id}.toList()..sort();
  final found = await Future.wait([
    api.getRecordingStrategy(tenant: tenant, scopeKind: 'TENANT', scopeId: tenant),
    for (final id in profileIds) api.getRecordingStrategy(tenant: tenant, scopeKind: 'PROFILE', scopeId: id),
  ]);
  return found.whereType<RecordingStrategyResponse>().toList();
});

String scopeKeyOf(RecordingStrategyResponse s) => '${s.scopeKind}/${s.scopeId}';

String strategyKindOf(RecordingStrategyResponse s) => '${s.strategy['kind'] ?? 'single'}'.toLowerCase();

/// Option, title, meaning, and an example of the settings each kind takes.
const _kinds = <(String, String, String, Map<String, Object>)>[
  ('single', 'One store', 'Every turn is written to a single recorder, which also serves reads.', {'primary': 'postgres'}),
  ('fanout', 'Primary plus copies', 'Reads come from the primary; writes are copied to each sink in the background through the outbox.',
      {'primary': 'postgres', 'sinks': <String>[]}),
  ('tiered', 'Hot, then cold', 'Recent conversations stay in a fast store and move to a cold store after the hot window.',
      {'hot': 'postgres', 'cold': 's3', 'hotWindow': 'P30D'}),
  ('classified', 'Route by tier', 'Each conversation goes to the store for its classification (e.g. data sensitivity), else the fallback.',
      {'byTier': <String, String>{}, 'fallback': 'postgres'}),
];

String _kindTitle(String kind) => _kinds.where((k) => k.$1 == kind).firstOrNull?.$2 ?? kind;

String _pretty(Object v) => const JsonEncoder.withIndent('  ').convert(v);

String _settingsSummary(Map<String, dynamic> strategy) {
  final parts = <String>[];
  strategy.forEach((k, v) {
    if (k == 'kind') return;
    parts.add(switch (v) {
      List l => '$k: ${l.length}',
      Map m => '$k: ${m.length}',
      _ => '$k: $v',
    });
  });
  return parts.isEmpty ? 'No settings' : parts.join(' · ');
}

const _labels = {
  'scopeKind': 'Applies to',
  'scopeId': 'Profile',
  'kind': 'Strategy',
  'settings': 'Settings',
};

class RecordingStrategiesScreen extends ConsumerStatefulWidget {
  const RecordingStrategiesScreen({super.key});
  @override
  ConsumerState<RecordingStrategiesScreen> createState() => _RecordingStrategiesScreenState();
}

class _RecordingStrategiesScreenState extends ConsumerState<RecordingStrategiesScreen> {
  String? _handledLink;

  Future<void> _open(RecordingStrategyResponse? existing) async {
    await showYSheet<void>(
      context,
      label: existing == null ? 'New recording strategy' : 'Edit recording strategy',
      builder: (_) => RecordingStrategyEditorSheet(existing: existing),
    );
  }

  /// `?id=<scopeId>` or `?id=TENANT/<id>` opens that strategy; `?new=1` the create sheet.
  void _handleDeepLink(List<RecordingStrategyResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((s) => scopeKeyOf(s) == link || s.scopeId == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No recording strategy for “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/recording-strategies');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Recording strategies');
    }
    final strategies = ref.watch(recordingStrategiesProvider);
    final profiles = ref.watch(profilesProvider).valueOrNull ?? const <ProfileResponse>[];
    final names = {for (final p in profiles) p.id: p.displayName};
    final scheme = Theme.of(context).colorScheme;
    strategies.whenData(_handleDeepLink);

    return YPage(
      title: 'Recording strategies',
      subtitle: 'Where conversations are stored. A profile’s own strategy wins over the tenant’s; '
          'the strategy is picked when a conversation starts.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New strategy')),
      ],
      child: LiveAsyncView<List<RecordingStrategyResponse>>(
        value: strategies,
        what: 'recording strategies',
        onRetry: () => ref.invalidate(recordingStrategiesProvider),
        data: (context, list) => YTable<RecordingStrategyResponse>(
          rows: list,
          noun: 'strategies',
          idOf: scopeKeyOf,
          searchHint: 'Search by profile or strategy',
          searchTextOf: (s) => '${s.scopeKind} ${s.scopeId} ${names[s.scopeId] ?? ''} ${strategyKindOf(s)} ${_kindTitle(strategyKindOf(s))}',
          onRowTap: _open,
          filters: [
            YFilter('Tenant-wide', (s) => s.scopeKind == 'TENANT'),
            YFilter('Profile overrides', (s) => s.scopeKind == 'PROFILE'),
            for (final k in _kinds) YFilter(k.$2, (s) => strategyKindOf(s) == k.$1),
          ],
          empty: YEmptyState(
            icon: Icons.storage_outlined,
            title: 'No recording strategies yet',
            message: 'A recording strategy decides where this tenant’s conversations are kept. Without one, the deployment’s default store is used.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Set a strategy')),
          ),
          columns: [
            YColumn(
              label: 'Applies to',
              flex: 3,
              sortKey: (s) => '${s.scopeKind == 'TENANT' ? 0 : 1}${s.scopeId}',
              cell: (context, s) => Row(children: [
                YBadge(s.scopeKind == 'TENANT' ? 'Tenant' : 'Profile', tone: s.scopeKind == 'TENANT' ? YTone.brand : YTone.neutral),
                const SizedBox(width: 10),
                Expanded(
                  child: s.scopeKind == 'TENANT'
                      ? const YCellStack(primary: 'Every profile without its own')
                      : YCellStack(primary: names[s.scopeId] ?? s.scopeId, secondary: s.scopeId),
                ),
              ]),
            ),
            YColumn(
              label: 'Strategy',
              flex: 2,
              sortKey: strategyKindOf,
              cell: (context, s) => YCellStack(primary: _kindTitle(strategyKindOf(s)), secondary: strategyKindOf(s)),
            ),
            YColumn(
              label: 'Settings',
              flex: 3,
              cell: (context, s) => Text(_settingsSummary(s.strategy),
                  overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5, color: scheme.onSurfaceVariant)),
            ),
            YColumn(
              label: 'Version',
              width: 120,
              sortKey: (s) => s.version,
              cell: (context, s) => Text('v${s.version}', style: monoStyle(context, size: 13, weight: FontWeight.w500)),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (s) => s.createdAt ?? '',
              cell: (context, s) => Text(formatRelativeIso(s.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Create a strategy at a scope, or publish a new version of one.
class RecordingStrategyEditorSheet extends ConsumerStatefulWidget {
  const RecordingStrategyEditorSheet({super.key, this.existing});
  final RecordingStrategyResponse? existing;

  @override
  ConsumerState<RecordingStrategyEditorSheet> createState() => _RecordingStrategyEditorSheetState();
}

class _RecordingStrategyEditorSheetState extends ConsumerState<RecordingStrategyEditorSheet> {
  final _settings = TextEditingController();
  String _scopeKind = 'TENANT';
  String? _profileId;
  String _kind = 'fanout';
  late final YFormController _form;
  RecordingStrategyResponse? _base;
  late final String _tenant;

  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _tenant = ref.read(currentTenantOrNull) ?? '';
    _base = widget.existing;
    _load(_base);
    _form = YFormController(
      snapshot: () => {
        'scopeKind': _scopeKind,
        'scopeId': _scopeId,
        'kind': _kind,
        'settings': _settings.text.trim(),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.recordingStrategy.$_tenant.${_base == null ? 'new' : scopeKeyOf(_base!)}',
      baseVersion: _base?.version,
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      setState(() => _applySnapshot(d.values));
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  String get _scopeId => _scopeKind == 'TENANT' ? _tenant : (_profileId ?? '');

  void _load(RecordingStrategyResponse? s) {
    if (s == null) {
      _settings.text = _pretty(_kinds.firstWhere((k) => k.$1 == _kind).$4);
      return;
    }
    _scopeKind = s.scopeKind;
    _profileId = s.scopeKind == 'PROFILE' ? s.scopeId : null;
    _kind = strategyKindOf(s);
    _settings.text = _pretty(Map<String, dynamic>.from(s.strategy)..remove('kind'));
  }

  void _applySnapshot(Map<String, Object?> v) {
    if (!_editing) {
      final scope = v['scopeKind'];
      if (scope == 'TENANT' || scope == 'PROFILE') _scopeKind = scope as String;
      final id = v['scopeId'];
      if (_scopeKind == 'PROFILE' && id is String && id.isNotEmpty) _profileId = id;
    }
    final kind = v['kind'];
    if (kind is String && _kinds.any((k) => k.$1 == kind)) _kind = kind;
    final settings = v['settings'];
    if (settings is String) _settings.text = settings;
  }

  /// The strategy already bound at the chosen scope, if any — a create there publishes over it.
  RecordingStrategyResponse? _current(List<RecordingStrategyResponse> all) =>
      all.where((s) => s.scopeKind == _scopeKind && s.scopeId == _scopeId).firstOrNull;

  Map<String, String?> _validate() {
    final (jsonErr, value) = Validators.jsonObject(_settings.text, allowEmpty: true);
    String? settingsErr = jsonErr;
    if (settingsErr == null && value != null && value.containsKey('kind')) {
      settingsErr = 'Remove “kind” — the strategy choice above sets it.';
    }
    return {
      'scopeId': _scopeKind == 'PROFILE' && (_profileId == null || _profileId!.isEmpty) ? 'Pick the profile this strategy is for.' : null,
      'settings': settingsErr,
    };
  }

  String? get _baseSettings => _base == null ? null : _pretty(Map<String, dynamic>.from(_base!.strategy)..remove('kind'));

  /// Swapping kinds swaps in that kind's example (or the saved settings, when
  /// going back to the saved kind) — unless the operator wrote their own.
  void _setKind(String kind) {
    final text = _settings.text.trim();
    final untouched = text.isEmpty || text == _baseSettings || _kinds.any((k) => _pretty(k.$4) == text);
    setState(() {
      _kind = kind;
      if (untouched) {
        _settings.text = _base != null && strategyKindOf(_base!) == kind
            ? _baseSettings!
            : _pretty(_kinds.firstWhere((k) => k.$1 == kind).$4);
      }
    });
    _form.refresh();
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    _settings.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final (_, extras) = Validators.jsonObject(_settings.text, allowEmpty: true);
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = await api.createRecordingStrategy(RecordingStrategyRequest(
        tenant: _tenant,
        scopeKind: _scopeKind,
        scopeId: _scopeId,
        strategy: {...?extras, 'kind': _kind},
      ));
      ref.invalidate(recordingStrategiesProvider);
      _form.saving = false;
      _form.markClean(baseVersion: saved.version);
      if (!mounted) return;
      final where = saved.scopeKind == 'TENANT' ? 'the tenant' : saved.scopeId;
      showToast(context, saved.version == 1 ? 'Set the recording strategy for $where' : 'Published v${saved.version} for $where');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final all = ref.watch(recordingStrategiesProvider).valueOrNull ?? const <RecordingStrategyResponse>[];
    final profiles = ref.watch(profilesProvider);
    final latestProfiles = <String, ProfileResponse>{};
    for (final p in profiles.valueOrNull ?? const <ProfileResponse>[]) {
      final cur = latestProfiles[p.id];
      if (cur == null || p.version > cur.version) latestProfiles[p.id] = p;
    }
    final withStrategy = {for (final s in all) if (s.scopeKind == 'PROFILE') s.scopeId: s.version};
    final current = _current(all);
    final over = !_editing && current != null;
    final next = ((_editing ? _form.baseVersion : current?.version) ?? 0) + 1;
    final scopeName = _scopeKind == 'TENANT' ? 'the tenant' : (latestProfiles[_scopeId]?.displayName ?? _scopeId);

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? 'Recording for $scopeName' : 'New recording strategy',
          subtitle: _editing
              ? 'v${_base!.version} is current. Saving publishes v$next; conversations already running keep the strategy they started with.'
              : 'Pick where it applies and how conversations are stored.',
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'publishing' : 'saving this strategy'),
                const SizedBox(height: Gap.lg),
              ],
              if (_editing)
                YFieldShell(
                  label: 'Applies to',
                  wireKey: 'scopeKind / scopeId',
                  helper: 'Fixed for this strategy. To cover a different scope, create a new one.',
                  child: Row(children: [
                    YBadge(_scopeKind == 'TENANT' ? 'Tenant' : 'Profile', tone: _scopeKind == 'TENANT' ? YTone.brand : YTone.neutral),
                    const SizedBox(width: 10),
                    Text(_scopeId, style: monoStyle(context, size: 13, weight: FontWeight.w500)),
                  ]),
                )
              else ...[
                YEnumCards<String>(
                  label: 'Applies to',
                  wireKey: 'scopeKind',
                  required: true,
                  horizontal: true,
                  form: _form,
                  name: 'scopeKind',
                  value: _scopeKind,
                  options: const [
                    YEnumOption('TENANT', 'The whole tenant', wire: 'TENANT',
                        description: 'The default for every profile that has no strategy of its own.'),
                    YEnumOption('PROFILE', 'One profile', wire: 'PROFILE',
                        description: 'Overrides the tenant’s strategy for conversations with that profile.'),
                  ],
                  onChanged: (v) {
                    setState(() => _scopeKind = v);
                    _form.refresh();
                  },
                ),
                if (_scopeKind == 'PROFILE') ...[
                  const SizedBox(height: Gap.fieldGap),
                  YResourcePicker(
                    label: 'Profile',
                    wireKey: 'scopeId',
                    form: _form,
                    name: 'scopeId',
                    required: true,
                    loading: profiles.isLoading,
                    value: _profileId,
                    hint: 'Search profiles…',
                    helper: 'Conversations with this profile are recorded with the strategy below.',
                    resources: [
                      for (final p in latestProfiles.values)
                        YResource(
                          id: p.id,
                          name: p.displayName,
                          meta: withStrategy[p.id] == null ? 'v${p.version}' : 'has a strategy · v${withStrategy[p.id]}',
                        ),
                    ],
                    onChanged: (v) {
                      setState(() => _profileId = v);
                      _form.refresh();
                    },
                  ),
                ],
              ],
              if (over) ...[
                const SizedBox(height: Gap.lg),
                YBanner(
                  tone: YBannerTone.info,
                  title: '${_scopeKind == 'TENANT' ? 'The tenant' : scopeName} already has a strategy (v${current.version}).',
                  message: 'Saving publishes v$next in its place. New conversations use it; running ones keep theirs.',
                ),
              ],
              const SizedBox(height: Gap.fieldGap),
              YEnumCards<String>(
                label: 'Strategy',
                wireKey: 'strategy.kind',
                required: true,
                form: _form,
                name: 'kind',
                value: _kind,
                options: [for (final k in _kinds) YEnumOption(k.$1, k.$2, wire: k.$1, description: k.$3)],
                onChanged: _setKind,
              ),
              const SizedBox(height: Gap.fieldGap),
              YJsonField(
                label: 'Settings',
                wireKey: 'strategy',
                controller: _settings,
                form: _form,
                name: 'settings',
                optional: true,
                minLines: 5,
                helper: 'Recorder names and options for this strategy, as a JSON object. Saved alongside “kind”; the example shows the keys each strategy takes.',
                onChanged: () => _form.changed('settings'),
              ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing || over ? 'Publish v$next' : 'Create strategy',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
