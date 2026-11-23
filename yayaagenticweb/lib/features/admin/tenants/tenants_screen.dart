import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../models/admin/clone.dart';
import '../../../models/admin/tenant.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/api_error.dart';
import '../../../ui/feedback/confirm.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/form/unsaved_guard.dart';
import '../../../ui/form/y_fields.dart';
import '../../../ui/form/y_form_controller.dart';
import '../../../ui/form/y_form_layout.dart';
import '../../../ui/tokens.dart';
import 'tenant_validators.dart';

/// Reactive feed of every tenant the operator can see. Refreshed on every
/// admin write that touches a tenant row (registration, lifecycle change,
/// migrator apply).
final tenantsProvider = FutureProvider<List<TenantResponse>>((ref) async {
  liveResource(ref, AdminResourceKind.tenant);
  final api = await ref.watch(adminApiProvider.future);
  return api.listTenants();
});

/// Per-tenant health view — invalidated whenever the tenant detail is
/// edited or a migrator run finishes.
final tenantHealthProvider =
    FutureProvider.family<TenantHealthResponse, String>((ref, id) async {
  liveResource(ref, AdminResourceKind.tenant, [AdminResourceKind.oidcIssuer, AdminResourceKind.jwtSecret, AdminResourceKind.authBinding, AdminResourceKind.profile, AdminResourceKind.serviceToken]);
  final api = await ref.watch(adminApiProvider.future);
  return api.tenantHealth(id);
});

bool _healthy(TenantHealthResponse h) => h.hostBaseUrlSet && h.warnings.isEmpty;

YBadge _statusBadge(String status) => switch (status) {
      'ACTIVE' => const YBadge('Active', tone: YTone.success, icon: Icons.check_circle_outline),
      'SUSPENDED' => const YBadge('Suspended', tone: YTone.warning, icon: Icons.pause_circle_outline),
      'ARCHIVED' => const YBadge('Archived', tone: YTone.neutral, icon: Icons.archive_outlined),
      _ => YBadge(status),
    };

const _labels = {
  'id': 'Tenant ID',
  'displayName': 'Display name',
  'hostBaseUrl': 'Host base URL',
  'hostBaseUrlAllowlist': 'Host base URL allowlist',
  'inboundOriginAllowlist': 'Allowed browser origins',
  'requireHttps': 'Require HTTPS',
};

class TenantsScreen extends ConsumerStatefulWidget {
  const TenantsScreen({super.key});
  @override
  ConsumerState<TenantsScreen> createState() => _TenantsScreenState();
}

class _TenantsScreenState extends ConsumerState<TenantsScreen> {
  String? _handledLink;

  Future<void> _open(TenantResponse? existing) async {
    await showYSheet<void>(
      context,
      width: 680,
      label: existing == null ? 'Register tenant' : 'Tenant ${existing.id}',
      builder: (_) => TenantEditorSheet(existing: existing),
    );
  }

  void _handleDeepLink(List<TenantResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((t) => t.id == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No tenant “$link”', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/tenants');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenants = ref.watch(tenantsProvider);
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.tenant));
    tenants.whenData(_handleDeepLink);

    return YPage(
      title: 'Tenants',
      subtitle: 'A tenant owns its trust bundle: the host its HTTP tools call, the browser origins it accepts, and its default auth and recording. Outbound and inbound trust never drift apart.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Register tenant')),
      ],
      child: LiveAsyncView<List<TenantResponse>>(
        value: tenants,
        what: 'tenants',
        onRetry: () => ref.invalidate(tenantsProvider),
        data: (context, list) => YTable<TenantResponse>(
          rows: list,
          noun: 'tenants',
          idOf: (t) => t.id,
          searchHint: 'Search by ID, name or host',
          searchTextOf: (t) => '${t.id} ${t.displayName} ${t.hostBaseUrl ?? ''} ${t.status}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('Active', (t) => t.status == 'ACTIVE'),
            YFilter('Suspended', (t) => t.status == 'SUSPENDED'),
            YFilter('Archived', (t) => t.status == 'ARCHIVED'),
            YFilter('No host URL', (t) => (t.hostBaseUrl ?? '').isEmpty),
          ],
          empty: YEmptyState(
            icon: Icons.apartment_outlined,
            title: 'No tenants yet',
            message: 'A tenant is one customer’s isolated agent setup — profiles, tools, knowledge and trust settings.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Register your first tenant')),
          ),
          columns: [
            YColumn(
              label: 'Tenant',
              flex: 3,
              sortKey: (t) => t.displayName.toLowerCase(),
              cell: (context, t) => YCellStack(primary: t.displayName, secondary: t.id),
            ),
            YColumn(label: 'Status', width: 130, sortKey: (t) => t.status, cell: (context, t) => _statusBadge(t.status)),
            YColumn(
              label: 'Host base URL',
              flex: 3,
              sortKey: (t) => t.hostBaseUrl ?? '',
              cell: (context, t) => (t.hostBaseUrl ?? '').isEmpty
                  ? const YBadge('Not set', tone: YTone.warning, icon: Icons.warning_amber_rounded)
                  : Text(t.hostBaseUrl!, overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
            ),
            YColumn(label: 'Health', width: 150, cell: (context, t) => _HealthCell(tenantId: t.id)),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (t) => t.updatedAt ?? t.createdAt ?? '',
              cell: (context, t) => Text(fresh.contains(t.id) ? 'Just now' : formatRelativeIso(t.updatedAt ?? t.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

class _HealthCell extends ConsumerWidget {
  const _HealthCell({required this.tenantId});
  final String tenantId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final h = ref.watch(tenantHealthProvider(tenantId));
    return h.when(
      skipLoadingOnReload: true,
      loading: () => const YBadge('Checking…', icon: Icons.more_horiz),
      error: (_, _) => const YBadge('Unknown', icon: Icons.help_outline),
      data: (h) => _healthy(h)
          ? const YBadge('Healthy', tone: YTone.success, icon: Icons.check)
          : Tooltip(
              message: [if (!h.hostBaseUrlSet) 'Host base URL not set', ...h.warnings].join('\n'),
              child: YBadge(
                '${h.warnings.isEmpty ? 1 : h.warnings.length} ${h.warnings.length > 1 ? 'warnings' : 'warning'}',
                tone: YTone.warning,
                icon: Icons.warning_amber_rounded,
              ),
            ),
    );
  }
}

/// Register a tenant, or edit one's routing — plus its health and lifecycle.
class TenantEditorSheet extends ConsumerStatefulWidget {
  const TenantEditorSheet({super.key, this.existing});
  final TenantResponse? existing;

  @override
  ConsumerState<TenantEditorSheet> createState() => _TenantEditorSheetState();
}

class _TenantEditorSheetState extends ConsumerState<TenantEditorSheet> {
  final _id = TextEditingController();
  final _displayName = TextEditingController();
  final _hostBase = TextEditingController();
  final _hostAllow = TextEditingController();
  final _inboundAllow = TextEditingController();
  bool _requireHttps = true;
  bool _busy = false;
  late final YFormController _form;
  late TenantResponse? _tenant = widget.existing;

  bool get _editing => _tenant != null;
  bool get _archived => _tenant?.status == 'ARCHIVED';

  @override
  void initState() {
    super.initState();
    final t = _tenant;
    _id.text = t?.id ?? '';
    _displayName.text = t?.displayName ?? '';
    _hostBase.text = t?.hostBaseUrl ?? '';
    _hostAllow.text = (t?.hostBaseUrlAllowlist ?? const []).join('\n');
    _inboundAllow.text = (t?.inboundOriginAllowlist ?? const []).join('\n');
    _requireHttps = t?.requireHttps ?? true;
    _form = YFormController(
      snapshot: () => {
        'id': _id.text.trim(),
        'displayName': _displayName.text.trim(),
        'hostBaseUrl': _hostBase.text.trim(),
        'hostBaseUrlAllowlist': TenantValidators.splitLines(_hostAllow.text),
        'inboundOriginAllowlist': TenantValidators.splitLines(_inboundAllow.text),
        'requireHttps': _requireHttps,
      },
      validate: _validate,
      labels: _labels,
      draftKey: _archived ? null : 'yaya.draft.tenant.platform.${t?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    if (!_archived) {
      _form.loadDraft().then((d) {
        if (d == null || !mounted) return;
        _applySnapshot(d.values);
        _form.refresh();
      });
    }
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  void _applySnapshot(Map<String, Object?> v) {
    if (!_editing) _id.text = (v['id'] as String?) ?? _id.text;
    _displayName.text = (v['displayName'] as String?) ?? _displayName.text;
    _hostBase.text = (v['hostBaseUrl'] as String?) ?? _hostBase.text;
    final ha = v['hostBaseUrlAllowlist'];
    if (ha is List) _hostAllow.text = ha.join('\n');
    final ia = v['inboundOriginAllowlist'];
    if (ia is List) _inboundAllow.text = ia.join('\n');
    final rh = v['requireHttps'];
    if (rh is bool) _requireHttps = rh;
  }

  Map<String, String?> _validate() {
    final known = ref.read(tenantsProvider).valueOrNull ?? const <TenantResponse>[];
    var idErr = _editing ? null : TenantValidators.tenantId(_id.text);
    if (idErr == null && !_editing) {
      final clash = known.where((t) => t.id == _id.text.trim()).firstOrNull;
      if (clash != null) {
        idErr = clash.status == 'ARCHIVED'
            ? 'A tenant with this ID was archived; archived IDs can’t be reused.'
            : 'This tenant ID is already taken.';
      }
    }
    return {
      'id': idErr,
      'displayName': Validators.required(_displayName.text, 'a display name'),
      'hostBaseUrl': TenantValidators.hostBaseUrl(_hostBase.text, requireHttps: _requireHttps),
      'hostBaseUrlAllowlist': TenantValidators.lines(
          _hostAllow.text, (l) => TenantValidators.urlPattern(l, requireHttps: _requireHttps)),
      'inboundOriginAllowlist': TenantValidators.lines(
          _inboundAllow.text, (l) => TenantValidators.originPattern(l, requireHttps: _requireHttps)),
    };
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_id, _displayName, _hostBase, _hostAllow, _inboundAllow]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    if (_archived || !_form.submit()) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final base = TenantRequest(
        id: _id.text.trim(),
        displayName: _displayName.text.trim(),
        hostBaseUrl: _hostBase.text.trim(),
        hostBaseUrlAllowlist: TenantValidators.splitLines(_hostAllow.text),
        inboundOriginAllowlist: TenantValidators.splitLines(_inboundAllow.text),
        requireHttps: _requireHttps,
      );
      final TenantResponse saved;
      if (_editing) {
        // PATCH: null default auth / recording ids mean "leave as is"; the
        // settings map is sent back whole so the patch doesn't empty it.
        saved = await api.patchTenant(_tenant!.id, base.copyWith(settings: _tenant!.settings));
        ref.invalidate(tenantHealthProvider(saved.id));
      } else {
        saved = await api.createTenant(base);
      }
      ref.invalidate(tenantsProvider);
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _editing ? 'Saved ${saved.id}' : 'Registered ${saved.id}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _lifecycle(Future<TenantResponse> Function(AdminApi api) op, String done) async {
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final t = await op(api);
      ref.invalidate(tenantsProvider);
      ref.invalidate(tenantHealthProvider(t.id));
      if (!mounted) return;
      setState(() => _tenant = t);
      showToast(context, done);
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _suspend() async {
    final t = _tenant!;
    final ok = await confirmAction(
      context,
      title: 'Suspend ${t.displayName}?',
      message: 'Tenant ${t.id} stops serving: new conversations and tool calls are refused until a platform admin resumes it. Its configuration and history are kept.',
      confirmLabel: 'Suspend tenant',
      destructive: true,
    );
    if (ok) await _lifecycle((api) => api.suspendTenant(t.id), 'Suspended ${t.id}');
  }

  Future<void> _resume() async {
    final t = _tenant!;
    final ok = await confirmAction(
      context,
      title: 'Resume ${t.displayName}?',
      message: 'Tenant ${t.id} starts serving conversations and tool calls again right away.',
      confirmLabel: 'Resume tenant',
    );
    if (ok) await _lifecycle((api) => api.resumeTenant(t.id), 'Resumed ${t.id}');
  }

  Future<void> _archive() async {
    final t = _tenant!;
    final ok = await confirmTyped(
      context,
      title: 'Archive ${t.displayName}?',
      message: 'This can’t be undone. Archived tenants refuse all new traffic, writes and tool dispatch, and can’t be resumed. '
          'The record stays for audit, and the ID “${t.id}” can never be registered again.',
      expected: t.id,
      confirmLabel: 'Archive permanently',
    );
    if (ok) await _lifecycle((api) => api.archiveTenant(t.id), 'Archived ${t.id}');
  }

  Future<void> _runMigrator() async {
    final t = _tenant!;
    setState(() => _busy = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final plan = await api.migrateToolsToPath(tenant: t.id, dryRun: true);
      if (!mounted) return;
      setState(() => _busy = false);
      final confirmed = await showDialog<bool>(context: context, builder: (_) => _MigratorDialog(plan: plan));
      if (confirmed != true) return;
      setState(() => _busy = true);
      await api.migrateToolsToPath(tenant: t.id, dryRun: false);
      ref.invalidate(tenantHealthProvider(t.id));
      if (mounted) {
        showToast(context, 'Rewrote ${plan.candidates.length} ${plan.candidates.length == 1 ? 'tool' : 'tools'} to relative paths');
      }
    } catch (e) {
      if (mounted) showToast(context, ApiErrorInfo.from(e).message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = _tenant;
    final readOnly = _archived;
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: t == null ? 'Register tenant' : t.displayName,
          subtitle: t == null
              ? 'Fields marked * are required. Platform admins only.'
              : '${t.id} · ${t.status.toLowerCase()}${t.createdAt == null ? '' : ' · registered ${formatRelativeIso(t.createdAt)}'}',
          headerActions: [if (t != null) Padding(padding: const EdgeInsets.only(top: 6, right: 8), child: _statusBadge(t.status))],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (readOnly) ...[
                const YBanner(
                  tone: YBannerTone.info,
                  title: 'Archived.',
                  message: 'This tenant refuses all traffic and its settings are read-only.',
                ),
                const SizedBox(height: Gap.lg),
              ],
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'registering this tenant'),
                const SizedBox(height: Gap.lg),
              ],
              YFormSection(
                title: 'Identity',
                card: false,
                children: [
                  YTextField(
                    label: 'Tenant ID',
                    wireKey: 'id',
                    controller: _id,
                    form: _form,
                    name: 'id',
                    required: !_editing,
                    readOnly: _editing,
                    autofocus: !_editing,
                    mono: true,
                    hint: 'e.g. acme-retail',
                    helper: _editing
                        ? 'Fixed — every resource of this tenant is keyed by it.'
                        : 'Lowercase letters, digits and dashes, 3–64 characters. It can’t change later.',
                  ),
                  YTextField(
                    label: 'Display name',
                    wireKey: 'displayName',
                    controller: _displayName,
                    form: _form,
                    name: 'displayName',
                    required: true,
                    readOnly: readOnly,
                    hint: 'e.g. Acme Retail',
                    helper: 'What operators see in the tenant switcher.',
                  ),
                ],
              ),
              YFormSection(
                title: 'Outbound — where HTTP tools call',
                card: false,
                children: [
                  YTextField(
                    label: 'Host base URL',
                    wireKey: 'hostBaseUrl',
                    controller: _hostBase,
                    form: _form,
                    name: 'hostBaseUrl',
                    required: true,
                    readOnly: readOnly,
                    mono: true,
                    keyboardType: TextInputType.url,
                    hint: 'e.g. https://api.acme.com',
                    helper: 'scheme://host[:port][/path], no ? or #. HTTP tools declare paths only; they resolve against this URL.',
                  ),
                  YTextField(
                    label: 'Host base URL allowlist',
                    wireKey: 'hostBaseUrlAllowlist',
                    controller: _hostAllow,
                    form: _form,
                    name: 'hostBaseUrlAllowlist',
                    optional: true,
                    readOnly: readOnly,
                    mono: true,
                    maxLines: 5,
                    minLines: 2,
                    hint: 'https://staging.acme.com\nhttps://*.acme.dev',
                    helper: 'One URL pattern per line, * wildcard allowed. A caller may switch the host per request with the X-Yaya-Host-Base-Url header, but only to a URL matching one of these.',
                  ),
                ],
              ),
              YFormSection(
                title: 'Inbound — which browsers may call in',
                card: false,
                children: [
                  YTextField(
                    label: 'Allowed browser origins',
                    wireKey: 'inboundOriginAllowlist',
                    controller: _inboundAllow,
                    form: _form,
                    name: 'inboundOriginAllowlist',
                    optional: true,
                    readOnly: readOnly,
                    mono: true,
                    maxLines: 5,
                    minLines: 2,
                    hint: 'https://shop.acme.com\nhttps://*.acme.com',
                    helper: 'One origin per line (scheme://host[:port], no path). Empty means only the host base URL’s own origin.',
                  ),
                  YSwitchField(
                    title: 'Require HTTPS',
                    wireKey: 'requireHttps',
                    value: _requireHttps,
                    description: 'Refuse http:// in the host base URL and both allowlists. Turn off only for local development.',
                    disabledReason: readOnly ? 'Archived tenants can’t be changed.' : null,
                    onChanged: (v) {
                      setState(() => _requireHttps = v);
                      _form.changed('requireHttps');
                      // The URL rules depend on this switch; re-check fields already shown.
                      if (_hostBase.text.trim().isNotEmpty) _form.touch('hostBaseUrl');
                    },
                  ),
                ],
              ),
              if (t != null) ...[
                if (t.defaultAuthenticatorBindingId != null || t.defaultRecordingStrategyId != null)
                  YFormSection(
                    title: 'Defaults',
                    description: 'Set from the auth bindings and recording strategies screens; saving here keeps them.',
                    card: false,
                    children: [
                      if (t.defaultAuthenticatorBindingId != null)
                        _kv(context, 'Default auth binding', t.defaultAuthenticatorBindingId!),
                      if (t.defaultRecordingStrategyId != null)
                        _kv(context, 'Default recording strategy', '#${t.defaultRecordingStrategyId}'),
                    ],
                  ),
                YFormSection(
                  title: 'Health',
                  card: false,
                  children: [_HealthPanel(tenantId: t.id, onMigrate: readOnly || _busy ? null : _runMigrator)],
                ),
                if (!readOnly)
                  YFormSection(
                    title: 'Lifecycle',
                    description: 'Platform admins only. A workspace can’t switch itself back on.',
                    card: false,
                    children: [
                      Wrap(spacing: 8, runSpacing: 8, children: [
                        if (t.status == 'ACTIVE')
                          OutlinedButton.icon(
                            onPressed: _busy ? null : _suspend,
                            icon: const Icon(Icons.pause_circle_outline, size: 18),
                            label: const Text('Suspend'),
                          ),
                        if (t.status == 'SUSPENDED')
                          FilledButton.tonalIcon(
                            onPressed: _busy ? null : _resume,
                            icon: const Icon(Icons.play_circle_outline, size: 18),
                            label: const Text('Resume'),
                          ),
                        OutlinedButton.icon(
                          onPressed: _busy ? null : _archive,
                          style: OutlinedButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
                          icon: const Icon(Icons.archive_outlined, size: 18),
                          label: const Text('Archive…'),
                        ),
                      ]),
                    ],
                  ),
              ],
            ],
          ),
          footer: readOnly
              ? null
              : YActionBar(
                  form: _form,
                  primaryLabel: _editing ? 'Save tenant' : 'Register tenant',
                  onPrimary: _save,
                  onCancel: () => closeYSheet(context),
                ),
        ),
      ),
    );
  }

  Widget _kv(BuildContext context, String label, String value) {
    final scheme = Theme.of(context).colorScheme;
    return Row(children: [
      SizedBox(width: 220, child: Text(label, style: TextStyle(fontSize: 14, color: scheme.onSurfaceVariant))),
      Expanded(child: SelectableText(value, style: monoStyle(context))),
    ]);
  }
}

class _HealthPanel extends ConsumerWidget {
  const _HealthPanel({required this.tenantId, required this.onMigrate});
  final String tenantId;
  final VoidCallback? onMigrate;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scheme = Theme.of(context).colorScheme;
    final status = StatusColors.of(context);
    final health = ref.watch(tenantHealthProvider(tenantId));
    Widget check(String label, bool ok, String okText, String badText) => Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Icon(ok ? Icons.check_circle : Icons.cancel, size: 18, color: ok ? status.success : scheme.error),
            const SizedBox(width: 8),
            Expanded(
              child: Text.rich(
                TextSpan(children: [
                  TextSpan(text: '$label — ', style: const TextStyle(fontWeight: FontWeight.w500)),
                  TextSpan(text: ok ? okText : badText),
                ]),
                style: TextStyle(fontSize: 14, height: 1.4, color: scheme.onSurface),
              ),
            ),
          ]),
        );
    return health.when(
      skipLoadingOnReload: true,
      loading: () => const YSkeletonRows(count: 2),
      error: (e, _) => YBanner(tone: YBannerTone.error, title: 'Couldn’t check health.', message: ApiErrorInfo.from(e).message),
      data: (h) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        check('Host base URL', h.hostBaseUrlSet, 'set', 'not set; HTTP tools refuse to run'),
        check('Default auth binding', h.authBindingResolves, 'resolves (or not used)', 'points at a missing binding'),
        check('Recording strategy', h.recordingStrategyResolves, 'resolves (or not used)', 'doesn’t resolve to a tenant-scoped strategy'),
        if (h.warnings.isNotEmpty) ...[
          const SizedBox(height: 6),
          for (final w in h.warnings)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: YBanner(tone: YBannerTone.warning, message: w),
            ),
        ],
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: onMigrate,
            icon: const Icon(Icons.compare_arrows, size: 18),
            label: const Text('Migrate absolute tool URLs to paths…'),
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(
            'Older HTTP tools may carry a full URL. This rewrites the ones on this tenant’s host to relative paths (each gets a new version); you review the plan first.',
            style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
          ),
        ),
      ]),
    );
  }
}

class _MigratorDialog extends StatelessWidget {
  const _MigratorDialog({required this.plan});
  final MigrateToPathPlan plan;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final n = plan.candidates.length;
    return AlertDialog(
      title: const Text('Migrate absolute URLs to paths'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Text(
              n == 0
                  ? 'Nothing to rewrite.${plan.unsafe.isEmpty ? '' : ' ${plan.unsafe.length} tools point at a different host and need a manual fix.'}'
                  : '$n ${n == 1 ? 'tool' : 'tools'} will be rewritten and get a new version. '
                      '${plan.unsafe.isEmpty ? '' : '${plan.unsafe.length} point at a different host and won’t be touched — fix those by hand.'}',
              style: const TextStyle(fontSize: 14, height: 1.5),
            ),
            const SizedBox(height: 12),
            if (plan.candidates.isNotEmpty) ...[
              Text('Will be rewritten', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
              const SizedBox(height: 6),
              for (final c in plan.candidates) _row(context, '${c.toolId} v${c.version}', c.current, c.rewritten),
              const SizedBox(height: 12),
            ],
            if (plan.unsafe.isNotEmpty) ...[
              Text('Not touched', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.error)),
              const SizedBox(height: 6),
              for (final u in plan.unsafe) _row(context, '${u.toolId} v${u.version}', u.current, u.reason, error: true),
            ],
          ]),
        ),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        FilledButton(
          onPressed: n == 0 ? null : () => Navigator.pop(context, true),
          child: Text(n == 0 ? 'Nothing to apply' : 'Rewrite $n ${n == 1 ? 'tool' : 'tools'}'),
        ),
      ],
    );
  }

  Widget _row(BuildContext context, String id, String from, String to, {bool error = false}) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(id, style: monoStyle(context, weight: FontWeight.w600)),
        Padding(
          padding: const EdgeInsets.only(left: 12, top: 2),
          child: Text('$from  →  $to', style: monoStyle(context, size: 12, color: error ? scheme.error : scheme.onSurfaceVariant)),
        ),
      ]),
    );
  }
}
