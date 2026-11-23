import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/identity_profile_mapping.dart';
import '../../../models/admin/profile.dart';
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
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../profiles/profiles_screen.dart' show profilesProvider;
import '../shared/admin_shared.dart';

final identityProfileMappingsProvider = FutureProvider.family<List<IdentityProfileMappingResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.identityRoute);
  final api = await ref.watch(adminApiProvider.future);
  return api.listIdentityProfileMappings(tenantId);
});

/// Profile id → its versions, newest first.
final _profileVersionsProvider = Provider<Map<String, List<ProfileResponse>>>((ref) {
  final out = <String, List<ProfileResponse>>{};
  for (final p in ref.watch(profilesProvider).valueOrNull ?? const <ProfileResponse>[]) {
    out.putIfAbsent(p.id, () => []).add(p);
  }
  for (final v in out.values) {
    v.sort((a, b) => b.version.compareTo(a.version));
  }
  return out;
});

const _labels = {
  'claimName': 'Claim',
  'claimValue': 'Value to match',
  'profileId': 'Profile',
  'profileVersion': 'Version',
  'priority': 'Priority',
};

/// The resolver walks rows by priority, then by id — the order shown.
List<IdentityProfileMappingResponse> _ordered(List<IdentityProfileMappingResponse> rows) =>
    [...rows]..sort((a, b) => a.priority != b.priority ? a.priority.compareTo(b.priority) : a.id.compareTo(b.id));

String _versionLabel(int? v) => v == null ? 'Latest' : 'Pinned v$v';

/// Per-tenant claim → profile routing read by `IdentityProfileResolver`.
class IdentityRoutingScreen extends ConsumerStatefulWidget {
  const IdentityRoutingScreen({super.key});

  @override
  ConsumerState<IdentityRoutingScreen> createState() => _IdentityRoutingScreenState();
}

class _IdentityRoutingScreenState extends ConsumerState<IdentityRoutingScreen> {
  String? _handledLink;

  Future<void> _open(IdentityProfileMappingResponse? existing) async {
    await showYSheet<void>(
      context,
      label: existing == null ? 'New identity route' : 'Edit identity route',
      builder: (_) => IdentityRouteEditorSheet(existing: existing),
    );
  }

  void _handleDeepLink(List<IdentityProfileMappingResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((r) => '${r.id}' == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No identity route “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/identity-routing');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Identity routing');
    }
    final mappings = ref.watch(identityProfileMappingsProvider(tenant));
    final profiles = ref.watch(_profileVersionsProvider);
    final profilesLoaded = ref.watch(profilesProvider).hasValue;
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.identityRoute));
    mappings.whenData(_handleDeepLink);

    bool missing(IdentityProfileMappingResponse r) {
      if (!profilesLoaded) return false;
      final versions = profiles[r.profileId];
      if (versions == null) return true;
      return r.profileVersion != null && !versions.any((p) => p.version == r.profileVersion);
    }

    return YPage(
      title: 'Identity routing',
      subtitle: 'Picks the profile from the signed-in user’s token claims when a conversation starts. Routes are checked from the '
          'lowest priority up and the first match wins; if none matches, the channel and tenant-default resolvers decide.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New route')),
      ],
      child: LiveAsyncView<List<IdentityProfileMappingResponse>>(
        value: mappings,
        what: 'identity routes',
        onRetry: () => ref.invalidate(identityProfileMappingsProvider(tenant)),
        data: (context, list) => YTable<IdentityProfileMappingResponse>(
          rows: _ordered(list),
          noun: 'routes',
          idOf: (r) => '${r.id}',
          searchHint: 'Search by claim, value or profile',
          searchTextOf: (r) => '${r.claimName} ${r.claimValue} ${r.profileId} ${profiles[r.profileId]?.first.displayName ?? ''}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('Profile missing', missing),
            YFilter('Pinned version', (r) => r.profileVersion != null),
          ],
          empty: YEmptyState(
            icon: Icons.alt_route_outlined,
            title: 'No identity routes yet',
            message: 'A route sends users whose token carries a claim — say roles contains “merchant” — to a profile. '
                'Without routes, each conversation needs an explicit profile or falls back to the tenant default.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Create your first route')),
          ),
          columns: [
            YColumn(
              label: 'Priority',
              width: 120,
              sortKey: (r) => r.priority,
              cell: (context, r) => Text('${r.priority}', style: monoStyle(context, weight: FontWeight.w600)),
            ),
            YColumn(
              label: 'When the token has',
              flex: 4,
              sortKey: (r) => '${r.claimName}=${r.claimValue}',
              cell: (context, r) => Text.rich(
                TextSpan(children: [
                  TextSpan(text: r.claimName, style: const TextStyle(fontWeight: FontWeight.w600)),
                  TextSpan(text: '  ∋  ', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
                  TextSpan(text: r.claimValue),
                ]),
                overflow: TextOverflow.ellipsis,
                style: monoStyle(context),
              ),
            ),
            YColumn(
              label: 'Profile',
              flex: 4,
              sortKey: (r) => r.profileId,
              cell: (context, r) => missing(r)
                  ? Row(children: [
                      Flexible(child: Text(r.profileId, overflow: TextOverflow.ellipsis, style: monoStyle(context))),
                      const SizedBox(width: 8),
                      const YBadge('Missing', tone: YTone.danger, icon: Icons.error_outline),
                    ])
                  : YCellStack(
                      primary: profiles[r.profileId]?.first.displayName ?? r.profileId,
                      secondary: '${r.profileId} · ${_versionLabel(r.profileVersion)}',
                    ),
            ),
            YColumn(
              label: 'Created',
              width: 120,
              sortKey: (r) => r.createdAt ?? '',
              cell: (context, r) => Text(fresh.contains('${r.id}') ? 'Just now' : formatRelativeIso(r.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

class IdentityRouteEditorSheet extends ConsumerStatefulWidget {
  const IdentityRouteEditorSheet({super.key, this.existing});
  final IdentityProfileMappingResponse? existing;

  @override
  ConsumerState<IdentityRouteEditorSheet> createState() => _IdentityRouteEditorSheetState();
}

class _IdentityRouteEditorSheetState extends ConsumerState<IdentityRouteEditorSheet> {
  final _claimName = TextEditingController();
  final _claimValue = TextEditingController();
  final _priority = TextEditingController();
  String? _profileId;
  int? _profileVersion;
  late final YFormController _form;

  IdentityProfileMappingResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _claimName.text = _base?.claimName ?? '';
    _claimValue.text = _base?.claimValue ?? '';
    _profileId = _base?.profileId;
    _profileVersion = _base?.profileVersion;
    _priority.text = '${_base?.priority ?? _nextPriority()}';
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'claimName': _claimName.text.trim(),
        'claimValue': _claimValue.text.trim(),
        'profileId': _profileId,
        'profileVersion': _profileVersion,
        'priority': _priority.text.trim(),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.identityRoute.$tenant.${_base?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      if (!_editing) {
        _claimName.text = (v['claimName'] as String?) ?? _claimName.text;
        _claimValue.text = (v['claimValue'] as String?) ?? _claimValue.text;
      }
      _profileId = v.containsKey('profileId') ? v['profileId'] as String? : _profileId;
      _profileVersion = v.containsKey('profileVersion') ? (v['profileVersion'] as num?)?.toInt() : _profileVersion;
      _priority.text = (v['priority'] as String?) ?? _priority.text;
      _form.refresh();
    });
  }

  /// New routes land after the existing ones, leaving room to insert between.
  int _nextPriority() {
    final tenant = ref.read(currentTenantOrNull);
    final rows = tenant == null ? const <IdentityProfileMappingResponse>[] : (ref.read(identityProfileMappingsProvider(tenant)).valueOrNull ?? const []);
    if (rows.isEmpty) return 100;
    return (rows.map((r) => r.priority).reduce((a, b) => a > b ? a : b) ~/ 10 + 1) * 10;
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_claimName, _claimValue, _priority]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, String?> _validate() {
    final versions = ref.read(_profileVersionsProvider);
    final profilesLoaded = ref.read(profilesProvider).hasValue;
    String? dupErr;
    if (!_editing) {
      final tenant = ref.read(currentTenantOrNull);
      final rows = tenant == null ? const <IdentityProfileMappingResponse>[] : (ref.read(identityProfileMappingsProvider(tenant)).valueOrNull ?? const []);
      final clash = rows.where((r) => r.claimName == _claimName.text.trim() && r.claimValue == _claimValue.text.trim()).firstOrNull;
      if (clash != null) dupErr = 'A route for this claim and value already exists (priority ${clash.priority}). Open it instead.';
    }
    String? profileErr;
    String? versionErr;
    if (_profileId == null || _profileId!.isEmpty) {
      profileErr = 'Pick the profile these users should get.';
    } else if (profilesLoaded && !versions.containsKey(_profileId)) {
      profileErr = '“$_profileId” doesn’t exist on this tenant. Pick another profile.';
    } else if (profilesLoaded && _profileVersion != null && !(versions[_profileId] ?? const []).any((p) => p.version == _profileVersion)) {
      versionErr = '$_profileId has no v$_profileVersion. Pick a published version or Latest.';
    }
    final p = _priority.text.trim();
    return {
      'claimName': _editing ? null : Validators.required(_claimName.text, 'the claim name, e.g. roles'),
      'claimValue': _editing ? null : (Validators.required(_claimValue.text, 'the value to match') ?? dupErr),
      'profileId': profileErr,
      'profileVersion': versionErr,
      'priority': p.isEmpty || int.tryParse(p) != null ? null : 'Enter a whole number, e.g. 100.',
    };
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = IdentityProfileMappingRequest(
        claimName: _claimName.text.trim(),
        claimValue: _claimValue.text.trim(),
        profileId: _profileId!,
        profileVersion: _profileVersion,
        priority: int.tryParse(_priority.text.trim()) ?? 100,
      );
      final saved = _editing
          ? await api.updateIdentityProfileMapping(tenant, _base!.id, body)
          : await api.createIdentityProfileMapping(tenant, body);
      ref.invalidate(identityProfileMappingsProvider(tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, '${_editing ? 'Saved' : 'Added'} route ${saved.claimName}=${saved.claimValue} → ${saved.profileId}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _delete() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null || _base == null) return;
    final ok = await confirmAction(
      context,
      title: 'Delete the route ${_base!.claimName}=${_base!.claimValue}?',
      message: 'Users with this claim stop being sent to ${_base!.profileId}. New conversations fall through to the next '
          'matching route, then to the channel or tenant default. Conversations already running keep their profile.',
      confirmLabel: 'Delete route',
      destructive: true,
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteIdentityProfileMapping(tenant, _base!.id);
      ref.invalidate(identityProfileMappingsProvider(tenant));
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Deleted route ${_base!.claimName}=${_base!.claimValue}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final versions = ref.watch(_profileVersionsProvider);
    final profilesAsync = ref.watch(profilesProvider);
    final name = _claimName.text.trim();
    final value = _claimValue.text.trim();
    final selectedVersions = _profileId == null ? const <ProfileResponse>[] : (versions[_profileId] ?? const <ProfileResponse>[]);

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? 'Identity route' : 'New identity route',
          subtitle: _editing ? '${_base!.claimName} = ${_base!.claimValue}' : 'Fields marked * are required.',
          headerActions: [
            if (_editing)
              TextButton.icon(
                onPressed: _form.saving ? null : _delete,
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('Delete'),
                style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              ),
          ],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'adding this route'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Claim',
                wireKey: 'claimName',
                controller: _claimName,
                form: _form,
                name: 'claimName',
                required: !_editing,
                readOnly: _editing,
                autofocus: !_editing,
                mono: true,
                hint: 'e.g. roles',
                helper: _editing
                    ? 'Fixed — to match a different claim, delete this route and add a new one.'
                    : 'A top-level claim in the user’s verified token — roles, groups, scope, a custom claim…',
                onChanged: (_) => setState(() {}),
              ),
              if (name.contains('.')) ...[
                const SizedBox(height: Gap.md),
                YBanner(
                  tone: YBannerTone.warning,
                  message: 'Nested claims aren’t read: this looks for a top-level claim literally named “$name”.',
                ),
              ],
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Value to match',
                wireKey: 'claimValue',
                controller: _claimValue,
                form: _form,
                name: 'claimValue',
                required: !_editing,
                readOnly: _editing,
                mono: true,
                hint: 'e.g. merchant',
                helper: _editing ? 'Fixed — to match a different value, delete this route and add a new one.' : 'Exact and case-sensitive.',
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: Gap.md),
              _MatchRules(claim: name.isEmpty ? 'roles' : name, value: value.isEmpty ? 'merchant' : value),
              if (RegExp(r'\s').hasMatch(value)) ...[
                const SizedBox(height: Gap.md),
                const YBanner(
                  tone: YBannerTone.warning,
                  message: 'This value has a space, so it only matches a claim that equals it exactly — never one word of a space-separated scope.',
                ),
              ],
              const SizedBox(height: Gap.xl),
              YResourcePicker(
                label: 'Profile',
                wireKey: 'profileId',
                form: _form,
                name: 'profileId',
                required: true,
                loading: profilesAsync.isLoading && !profilesAsync.hasValue,
                value: _profileId,
                hint: 'Search profiles…',
                helper: 'Users who match get this profile when they start a conversation.',
                resources: [
                  for (final e in versions.entries)
                    YResource(
                      id: e.key,
                      name: e.value.first.displayName,
                      meta: 'v${e.value.first.version}${e.value.length > 1 ? ' · ${e.value.length} versions' : ''}',
                    ),
                ],
                onChanged: (v) => setState(() {
                  if (v != _profileId) _profileVersion = null;
                  _profileId = v;
                }),
              ),
              const SizedBox(height: Gap.fieldGap),
              // 0 stands in for "latest" (null on the wire): a dropdown can't show a null selection.
              YSelectField<int>(
                label: 'Version',
                wireKey: 'profileVersion',
                form: _form,
                name: 'profileVersion',
                value: _profileVersion ?? 0,
                helper: _profileVersion == null
                    ? 'Follows new publishes: each conversation gets the newest version at the time it starts.'
                    : 'Pinned: stays on v$_profileVersion even after a newer version is published.',
                options: [
                  const YOption<int>(0, 'Latest (follows new publishes)'),
                  for (final p in selectedVersions) YOption<int>(p.version, 'Pin to v${p.version}', meta: p.status),
                  if (_profileVersion != null && !selectedVersions.any((p) => p.version == _profileVersion))
                    YOption<int>(_profileVersion!, 'v$_profileVersion — not published'),
                ],
                onChanged: (v) => setState(() => _profileVersion = v == null || v == 0 ? null : v),
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Priority',
                wireKey: 'priority',
                controller: _priority,
                form: _form,
                name: 'priority',
                optional: true,
                mono: true,
                keyboardType: TextInputType.number,
                hint: 'e.g. 100',
                helper: 'Lower is checked first; the first matching route wins. Steps of 10 leave room to insert one later. Blank = 100.',
              ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save route' : 'Add route',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

/// The three claim shapes the resolver accepts, shown with the operator's own
/// claim and value so "does my token match?" is answerable at a glance.
class _MatchRules extends StatelessWidget {
  const _MatchRules({required this.claim, required this.value});
  final String claim;
  final String value;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final rows = [
      ('A text claim', 'equals the value', '"$claim": "$value"'),
      ('A list claim', 'contains the value', '"$claim": ["staff", "$value"]'),
      ('A space-separated claim', 'has the value as one of its words (the OIDC scope style)', '"$claim": "openid $value"'),
    ];
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: scheme.surfaceContainer, borderRadius: BorderRadius.circular(10)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('A token matches when its claim…', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: scheme.onSurface)),
        for (final r in rows)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text.rich(
                TextSpan(children: [
                  TextSpan(text: r.$1, style: const TextStyle(fontWeight: FontWeight.w500)),
                  TextSpan(text: ' ${r.$2}'),
                ]),
                style: TextStyle(fontSize: 13, height: 1.4, color: scheme.onSurfaceVariant),
              ),
              const SizedBox(height: 2),
              Text(r.$3, style: monoStyle(context, size: 12.5, color: scheme.onSurface)),
            ]),
          ),
      ]),
    );
  }
}
