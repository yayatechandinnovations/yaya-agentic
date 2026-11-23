import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_resolver.dart';
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
import '../../../ui/form/y_resource_picker.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';

/// Also feeds the resolver pickers on channel bindings and chat entry points.
final authResolversProvider = FutureProvider.family<AuthResolverListResponse, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.authResolver);
  final api = await ref.watch(adminApiProvider.future);
  return api.listAuthResolvers(tenantId);
});

const _labels = {
  'name': 'Name',
  'description': 'Description',
  'kind': 'Kind',
  'beanName': 'Resolver bean',
  'httpPath': 'Webhook path',
  'serviceAuthKind': 'How yaya authenticates to your webhook',
  'serviceSecret': 'Service secret',
  'serviceJwtKid': 'Signing key ID',
  'serviceJwtAudience': 'Audience override',
  'timeoutMs': 'Timeout',
  'cacheEnabled': 'Cache tokens',
  'refreshWindowSeconds': 'Refresh window',
  'maxTokenAgeSeconds': 'Max token age',
  'maxCacheSeconds': 'Cache ceiling',
  'onRefreshFailure': 'When a refresh fails',
  'status': 'Status',
};

const _authKinds = {'NONE': 'No auth', 'BEARER': 'Bearer secret', 'SIGNED_JWT': 'Signed JWT'};

bool _needsSecret(String? k) => k == 'BEARER' || k == 'SIGNED_JWT';

/// Per-tenant `channel_auth_resolvers`: the identity webhooks (or in-process
/// beans) that turn a verified inbound channel caller into a tenant-issued JWT.
class AuthResolversScreen extends ConsumerStatefulWidget {
  const AuthResolversScreen({super.key});

  @override
  ConsumerState<AuthResolversScreen> createState() => _AuthResolversScreenState();
}

class _AuthResolversScreenState extends ConsumerState<AuthResolversScreen> {
  String? _handledLink;

  Future<void> _open(AuthResolverResponse? existing) async {
    await showYSheet<void>(
      context,
      width: 800,
      label: existing == null ? 'New auth resolver' : 'Edit auth resolver',
      builder: (_) => AuthResolverEditorSheet(existing: existing),
    );
  }

  void _handleDeepLink(AuthResolverListResponse data) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : data.resolvers.where((r) => r.name == link || '${r.id}' == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No auth resolver “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/auth-resolvers');
      _handledLink = null;
    });
  }

  Future<void> _clearCache(String tenant) async {
    final ok = await confirmAction(
      context,
      title: 'Clear every cached token for this tenant?',
      message: 'The next inbound message from every caller re-runs its resolver, which adds latency and load on your identity '
          'webhook for a while. Do this after rotating a secret or changing what your webhook returns.',
      confirmLabel: 'Clear cached tokens',
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final resp = await api.invalidateAuthResolverCache(tenant, AuthResolverInvalidateRequest(allForTenant: true));
      if (mounted) showToast(context, resp.count == 1 ? 'Cleared 1 cached token' : 'Cleared ${resp.count} cached tokens');
    } catch (e) {
      if (mounted) showToast(context, 'Couldn’t clear the cache: ${ApiErrorInfo.from(e).message}', error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Auth resolvers');
    }
    final resolvers = ref.watch(authResolversProvider(tenant));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.authResolver));
    resolvers.whenData(_handleDeepLink);

    return YPage(
      title: 'Auth resolvers',
      subtitle: 'Turn a verified channel caller (a WhatsApp or SMS phone) into a token your own identity service issues. '
          'yaya-agentic runs none of your identity logic — it asks your webhook, caches the answer and verifies it.',
      actions: [
        OutlinedButton.icon(
          onPressed: () => _clearCache(tenant),
          icon: const Icon(Icons.cleaning_services_outlined, size: 18),
          label: const Text('Clear cached tokens'),
        ),
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New resolver')),
      ],
      child: LiveAsyncView<AuthResolverListResponse>(
        value: resolvers,
        what: 'auth resolvers',
        onRetry: () => ref.invalidate(authResolversProvider(tenant)),
        data: (context, data) => YTable<AuthResolverResponse>(
          rows: data.resolvers,
          noun: 'resolvers',
          idOf: (r) => '${r.id}',
          searchHint: 'Search by name, path or bean',
          searchTextOf: (r) => '${r.name} ${r.description ?? ''} ${r.httpPath ?? ''} ${r.beanName ?? ''}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('Webhook', (r) => r.kind == 'HTTP'),
            YFilter('Bean', (r) => r.kind == 'BEAN'),
            YFilter('Deactivated', (r) => r.status != 'ACTIVE'),
            YFilter('Cache off', (r) => r.cacheEnabled == false),
          ],
          empty: YEmptyState(
            icon: Icons.fingerprint_outlined,
            title: 'No auth resolvers yet',
            message: 'Without one, channel callers get only the baseline phone identity (an anonymous-grade principal). '
                'Add a resolver to look them up in your own system.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Create your first resolver')),
          ),
          columns: [
            YColumn(
              label: 'Resolver',
              flex: 3,
              sortKey: (r) => r.name,
              cell: (context, r) => YCellStack(
                primary: r.name,
                mono: true,
                secondary: (r.description ?? '').isEmpty ? null : r.description,
              ),
            ),
            YColumn(
              label: 'Calls',
              flex: 3,
              sortKey: (r) => r.httpPath ?? r.beanName ?? '',
              cell: (context, r) => YCellStack(
                primary: r.kind == 'HTTP' ? (r.httpPath ?? '') : (r.beanName ?? ''),
                secondary: r.kind == 'HTTP' ? 'Webhook · ${r.timeoutMs ?? 3000} ms timeout' : 'In-process bean',
              ),
            ),
            YColumn(
              label: 'Service auth',
              width: 170,
              sortKey: (r) => r.serviceAuthKind ?? '',
              cell: (context, r) => r.kind != 'HTTP'
                  ? const Text('—')
                  : YBadge(
                      _authKinds[r.serviceAuthKind ?? 'NONE'] ?? r.serviceAuthKind!,
                      tone: (r.serviceAuthKind ?? 'NONE') == 'NONE' ? YTone.warning : YTone.neutral,
                    ),
            ),
            YColumn(
              label: 'Cache',
              width: 120,
              sortKey: (r) => r.cacheEnabled == false ? 0 : 1,
              cell: (context, r) => Text(r.cacheEnabled == false ? 'Off' : 'On · ${r.refreshWindowSeconds ?? 60}s early'),
            ),
            YColumn(
              label: 'Status',
              width: 130,
              sortKey: (r) => r.status,
              cell: (context, r) => r.status == 'ACTIVE'
                  ? const YBadge('Active', tone: YTone.success, icon: Icons.check_circle_outline)
                  : const YBadge('Deactivated', tone: YTone.neutral, icon: Icons.pause_circle_outline),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (r) => r.updatedAt ?? r.createdAt ?? '',
              cell: (context, r) => Text(fresh.contains('${r.id}') ? 'Just now' : formatRelativeIso(r.updatedAt ?? r.createdAt)),
            ),
            YColumn(
              label: '',
              width: 96,
              alignEnd: true,
              cell: (context, r) => TextButton.icon(
                onPressed: () => showAuthResolverProbe(context, tenant: tenant, resolver: r.name),
                icon: const Icon(Icons.bolt_outlined, size: 18),
                label: const Text('Test'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class AuthResolverEditorSheet extends ConsumerStatefulWidget {
  const AuthResolverEditorSheet({super.key, this.existing});
  final AuthResolverResponse? existing;

  @override
  ConsumerState<AuthResolverEditorSheet> createState() => _AuthResolverEditorSheetState();
}

class _AuthResolverEditorSheetState extends ConsumerState<AuthResolverEditorSheet> {
  final _name = TextEditingController();
  final _description = TextEditingController();
  final _httpPath = TextEditingController();
  final _secret = TextEditingController();
  final _kid = TextEditingController();
  final _audience = TextEditingController();
  final _timeout = TextEditingController();
  final _refreshWindow = TextEditingController();
  final _maxTokenAge = TextEditingController();
  final _maxCache = TextEditingController();
  final _kindDisplay = TextEditingController();

  String _kind = 'HTTP';
  String? _beanName;
  String _serviceAuthKind = 'SIGNED_JWT';
  String _onRefreshFailure = 'REJECT';
  bool _active = true;
  bool _cacheEnabled = true;
  late final YFormController _form;

  AuthResolverResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  /// A secret already exists server-side for the auth kind it was saved with.
  bool get _secretStored => _editing && (_base!.serviceSecretMasked ?? '').isNotEmpty && _needsSecret(_base!.serviceAuthKind);

  @override
  void initState() {
    super.initState();
    final b = _base;
    _name.text = b?.name ?? '';
    _description.text = b?.description ?? '';
    _httpPath.text = b?.httpPath ?? '';
    _kid.text = b?.serviceJwtKid ?? '';
    _audience.text = b?.serviceJwtAudience ?? '';
    _timeout.text = '${b?.timeoutMs ?? 3000}';
    _refreshWindow.text = '${b?.refreshWindowSeconds ?? 60}';
    _maxTokenAge.text = '${b?.maxTokenAgeSeconds ?? 3600}';
    _maxCache.text = b?.maxCacheSeconds?.toString() ?? '';
    if (b != null) {
      _kind = b.kind;
      _beanName = b.beanName;
      _serviceAuthKind = b.serviceAuthKind ?? 'NONE';
      _onRefreshFailure = b.onRefreshFailure ?? 'REJECT';
      _active = b.status == 'ACTIVE';
      _cacheEnabled = b.cacheEnabled ?? true;
      _kindDisplay.text = b.kind == 'HTTP' ? 'Webhook (HTTP)' : 'In-process bean (BEAN)';
    }
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'name': _name.text.trim(),
        'description': _description.text.trim(),
        'kind': _kind,
        'beanName': _beanName,
        'httpPath': _httpPath.text.trim(),
        'serviceAuthKind': _serviceAuthKind,
        'serviceSecret': _secret.text,
        'serviceJwtKid': _kid.text.trim(),
        'serviceJwtAudience': _audience.text.trim(),
        'timeoutMs': _timeout.text.trim(),
        'cacheEnabled': _cacheEnabled,
        'refreshWindowSeconds': _refreshWindow.text.trim(),
        'maxTokenAgeSeconds': _maxTokenAge.text.trim(),
        'maxCacheSeconds': _maxCache.text.trim(),
        'onRefreshFailure': _onRefreshFailure,
        'status': _active ? 'ACTIVE' : 'DEACTIVATED',
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.authResolver.$tenant.${_base?.name ?? 'new'}',
      excludeFromDraft: const {'serviceSecret'},
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _applySnapshot(d.values);
      _form.refresh();
    });
  }

  void _applySnapshot(Map<String, Object?> v) {
    String? s(String k) => v[k] is String ? v[k] as String : null;
    if (!_editing) {
      _name.text = s('name') ?? _name.text;
      _kind = s('kind') ?? _kind;
    }
    _description.text = s('description') ?? _description.text;
    _beanName = v.containsKey('beanName') ? s('beanName') : _beanName;
    _httpPath.text = s('httpPath') ?? _httpPath.text;
    _serviceAuthKind = s('serviceAuthKind') ?? _serviceAuthKind;
    _kid.text = s('serviceJwtKid') ?? _kid.text;
    _audience.text = s('serviceJwtAudience') ?? _audience.text;
    _timeout.text = s('timeoutMs') ?? _timeout.text;
    _refreshWindow.text = s('refreshWindowSeconds') ?? _refreshWindow.text;
    _maxTokenAge.text = s('maxTokenAgeSeconds') ?? _maxTokenAge.text;
    _maxCache.text = s('maxCacheSeconds') ?? _maxCache.text;
    _onRefreshFailure = s('onRefreshFailure') ?? _onRefreshFailure;
    if (v['cacheEnabled'] is bool) _cacheEnabled = v['cacheEnabled'] as bool;
    if (s('status') != null) _active = s('status') == 'ACTIVE';
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_name, _description, _httpPath, _secret, _kid, _audience, _timeout, _refreshWindow, _maxTokenAge, _maxCache, _kindDisplay]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, String?> _validate() {
    final tenant = ref.read(currentTenantOrNull);
    final data = tenant == null ? null : ref.read(authResolversProvider(tenant)).valueOrNull;
    String? nameErr;
    if (!_editing) {
      final n = _name.text.trim();
      nameErr = Validators.required(n, 'a resolver name');
      if (nameErr == null && n.length > 64) nameErr = 'Keep it to 64 characters or fewer.';
      if (nameErr == null && RegExp(r'[\s/?#]').hasMatch(n)) nameErr = 'Use no spaces or / ? # — bindings refer to the resolver by this name.';
      if (nameErr == null && (data?.resolvers ?? const []).any((r) => r.name == n)) {
        nameErr = 'A resolver with this name already exists. Open it instead.';
      }
    }
    final http = _kind == 'HTTP';
    String? beanErr;
    if (!http) {
      if (_beanName == null || _beanName!.isEmpty) {
        beanErr = 'Pick the resolver bean to call.';
      } else if (!_editing && data != null && !data.registeredBeanNames.contains(_beanName)) {
        beanErr = '“$_beanName” isn’t registered on this deployment.';
      }
    }
    String? pathErr;
    if (http) {
      final p = _httpPath.text.trim();
      if (p.isEmpty) {
        pathErr = 'Enter the webhook path, e.g. /yaya/resolve-identity.';
      } else if (!p.startsWith('/')) {
        pathErr = 'Start with / — the path is added to the tenant’s host base URL.';
      } else if (p.contains('://') || p.contains('?') || p.contains('#')) {
        pathErr = 'Just the path: no scheme or host (://), query (?) or fragment (#).';
      }
    }
    String? secretErr;
    if (http && _needsSecret(_serviceAuthKind) && _secret.text.trim().isEmpty && !_secretStored) {
      secretErr = 'Enter the secret your webhook expects for ${_authKinds[_serviceAuthKind]}.';
    }
    String? maxCacheErr;
    final mc = _maxCache.text.trim();
    if (mc.isNotEmpty && ((int.tryParse(mc) ?? 0) < 1)) maxCacheErr = 'Enter a whole number of seconds, 1 or more.';
    return {
      'name': nameErr,
      'beanName': beanErr,
      'httpPath': pathErr,
      'serviceSecret': secretErr,
      'serviceJwtKid': http && _kid.text.trim().length > 128 ? 'Keep it to 128 characters or fewer.' : null,
      'serviceJwtAudience': http && _audience.text.trim().length > 255 ? 'Keep it to 255 characters or fewer.' : null,
      'timeoutMs': http ? Validators.intInRange(_timeout.text, 100, 30000, optional: false) : null,
      'refreshWindowSeconds': Validators.intInRange(_refreshWindow.text, 0, 600, optional: false),
      'maxTokenAgeSeconds': Validators.intInRange(_maxTokenAge.text, 60, 86400, optional: false),
      'maxCacheSeconds': maxCacheErr,
    };
  }

  AuthResolverRequest _request() {
    final http = _kind == 'HTTP';
    final jwt = http && _serviceAuthKind == 'SIGNED_JWT';
    return AuthResolverRequest(
      name: _name.text.trim(),
      description: _description.text.trim(),
      kind: _kind,
      beanName: http ? null : _beanName,
      httpPath: http ? _httpPath.text.trim() : null,
      serviceAuthKind: http ? _serviceAuthKind : null,
      serviceSecret: http && _needsSecret(_serviceAuthKind) && _secret.text.trim().isNotEmpty ? _secret.text.trim() : null,
      serviceJwtKid: jwt ? _kid.text.trim() : null,
      serviceJwtAudience: jwt ? _audience.text.trim() : null,
      timeoutMs: http ? int.tryParse(_timeout.text.trim()) : null,
      cacheEnabled: _cacheEnabled,
      maxCacheSeconds: int.tryParse(_maxCache.text.trim()),
      refreshWindowSeconds: int.tryParse(_refreshWindow.text.trim()),
      maxTokenAgeSeconds: int.tryParse(_maxTokenAge.text.trim()),
      onRefreshFailure: _onRefreshFailure,
      status: _active ? 'ACTIVE' : 'DEACTIVATED',
    );
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final body = _request();
    final rotating = _editing && _secretStored && body.serviceSecret != null;
    if (rotating) {
      final ok = await confirmAction(
        context,
        title: 'Rotate the service secret for ${_base!.name}?',
        message: 'From the next call, yaya authenticates to your webhook with the new secret. If your webhook still expects '
            'the old one it refuses those calls and callers can’t be resolved — update its verifier first.',
        confirmLabel: 'Rotate secret',
        destructive: true,
      );
      if (!ok) return;
    }
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final saved = _editing ? await api.updateAuthResolver(tenant, _base!.name, body) : await api.createAuthResolver(tenant, body);
      ref.invalidate(authResolversProvider(tenant));
      _secret.clear();
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, !_editing ? 'Created ${saved.name}' : rotating ? 'Saved ${saved.name} · secret rotated' : 'Saved ${saved.name}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.saving = false;
      _form.applyServerError(e);
    }
  }

  Future<void> _delete() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null || _base == null) return;
    final ok = await confirmTyped(
      context,
      title: 'Delete the auth resolver ${_base!.name}?',
      message: 'Channel bindings that use it fall back to their “on unresolved” policy on the next inbound message, and every '
          'cached token for this tenant is cleared — all callers are re-resolved.',
      expected: _base!.name,
      confirmLabel: 'Delete resolver',
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final resp = await api.deleteAuthResolver(tenant, _base!.name);
      ref.invalidate(authResolversProvider(tenant));
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Deleted ${resp.name} · ${resp.cacheEntriesEvicted} cached tokens cleared');
      Navigator.of(context).pop();
    } catch (e) {
      _form.applyServerError(e);
    }
  }

  Widget _number(TextEditingController c, String name, String label, {required String unit, required String helper, bool optional = false, String? hint}) {
    return YTextField(
      label: label,
      wireKey: name,
      controller: c,
      form: _form,
      name: name,
      required: !optional,
      optional: optional,
      mono: true,
      hint: hint,
      keyboardType: TextInputType.number,
      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
      suffix: Padding(
        padding: const EdgeInsets.only(right: 12),
        child: Align(widthFactor: 1, child: Text(unit, style: TextStyle(fontSize: 13, color: Theme.of(context).colorScheme.onSurfaceVariant))),
      ),
      helper: helper,
    );
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    final data = tenant == null ? null : ref.watch(authResolversProvider(tenant)).valueOrNull;
    final beans = data?.registeredBeanNames ?? const <String>[];
    final http = _kind == 'HTTP';
    final scheme = Theme.of(context).colorScheme;
    final dropsSecret = _editing && _secretStored && http && !_needsSecret(_serviceAuthKind);

    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? _base!.name : 'New auth resolver',
          subtitle: _editing
              ? '${http ? 'Webhook' : 'In-process bean'} · ${_active ? 'active' : 'deactivated'}. Saving applies to the next inbound message; cached tokens stay until they expire.'
              : 'Fields marked * are required.',
          headerActions: [
            if (_editing) ...[
              TextButton.icon(
                onPressed: () => showAuthResolverProbe(context, tenant: tenant!, resolver: _base!.name, unsaved: _form.isDirty),
                icon: const Icon(Icons.bolt_outlined, size: 18),
                label: const Text('Test'),
              ),
              TextButton.icon(
                onPressed: _form.saving ? null : _delete,
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('Delete'),
                style: TextButton.styleFrom(foregroundColor: scheme.error),
              ),
            ],
          ],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 12, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                const SizedBox(height: Gap.sm),
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'creating this resolver'),
              ],
              YFormSection(
                title: 'Basics',
                card: false,
                children: [
                  YTextField(
                    label: 'Name',
                    wireKey: 'name',
                    controller: _name,
                    form: _form,
                    name: 'name',
                    required: !_editing,
                    readOnly: _editing,
                    autofocus: !_editing,
                    mono: true,
                    hint: 'e.g. acme-customer-lookup',
                    helper: _editing ? 'Fixed — channel bindings refer to the resolver by this name.' : 'Channel bindings refer to the resolver by this name; it can’t change later.',
                  ),
                  YTextField(
                    label: 'Description',
                    wireKey: 'description',
                    controller: _description,
                    form: _form,
                    name: 'description',
                    optional: true,
                    hint: 'e.g. Looks up loyalty members by phone',
                  ),
                  if (_editing)
                    YTextField(
                      label: 'Kind',
                      wireKey: 'kind',
                      controller: _kindDisplay,
                      readOnly: true,
                      helper: 'Fixed — to switch between webhook and bean, delete this resolver and create a new one.',
                    )
                  else
                    YEnumCards<String>(
                      label: 'Kind',
                      wireKey: 'kind',
                      form: _form,
                      name: 'kind',
                      required: true,
                      horizontal: true,
                      value: _kind,
                      onChanged: (v) => setState(() => _kind = v),
                      options: const [
                        YEnumOption('HTTP', 'Webhook', wire: 'HTTP', description: 'yaya calls an endpoint on your tenant’s host. The usual choice.'),
                        YEnumOption('BEAN', 'In-process bean', wire: 'BEAN', description: 'A resolver compiled into this deployment. Self-hosted only.'),
                      ],
                    ),
                  YSwitchField(
                    title: 'Active',
                    wireKey: 'status',
                    value: _active,
                    description: _active
                        ? 'Bindings that use this resolver call it for new callers.'
                        : 'Deactivated: calls fail as a configuration error and bindings fall back to their “on unresolved” policy.',
                    onChanged: (v) {
                      _form.changed('status');
                      setState(() => _active = v);
                    },
                  ),
                ],
              ),
              const Divider(height: Gap.xl),
              if (http)
                YFormSection(
                  title: 'Webhook',
                  description: 'yaya POSTs the verified caller to this path and expects a JWT back, which it verifies against this tenant’s OIDC issuers and JWT secrets.',
                  card: false,
                  children: [
                    YTextField(
                      label: 'Webhook path',
                      wireKey: 'httpPath',
                      controller: _httpPath,
                      form: _form,
                      name: 'httpPath',
                      required: true,
                      mono: true,
                      hint: 'e.g. /yaya/resolve-identity',
                      helper: 'Added to the tenant’s host base URL.',
                    ),
                    YEnumCards<String>(
                      label: 'How yaya authenticates to your webhook',
                      wireKey: 'serviceAuthKind',
                      form: _form,
                      name: 'serviceAuthKind',
                      required: true,
                      value: _serviceAuthKind,
                      onChanged: (v) => setState(() => _serviceAuthKind = v),
                      options: const [
                        YEnumOption('SIGNED_JWT', 'Signed JWT (recommended)', wire: 'SIGNED_JWT',
                            description: 'Each call carries a short-lived JWT signed with the shared secret, so a captured request can’t be replayed for long.'),
                        YEnumOption('BEARER', 'Bearer secret', wire: 'BEARER',
                            description: 'Each call carries the same static secret. Anyone who sees one request can call your webhook.'),
                        YEnumOption('NONE', 'No auth', wire: 'NONE', caution: true,
                            description: 'Your webhook gets no proof the call came from yaya. Only for a webhook on a private network.'),
                      ],
                    ),
                    if (_needsSecret(_serviceAuthKind))
                      YSecretField(
                        label: _secretStored ? 'New service secret' : 'Service secret',
                        wireKey: 'serviceSecret',
                        controller: _secret,
                        form: _form,
                        name: 'serviceSecret',
                        required: true,
                        stored: _secretStored,
                        helper: _secretStored
                            ? 'Stored (${_base!.serviceSecretMasked}). Leave blank to keep it; a new value rotates it when you save.'
                            : 'Shared with your webhook. Stored encrypted; you won’t see it again.',
                      ),
                    if (dropsSecret)
                      const YBanner(tone: YBannerTone.warning, message: 'Saving with “No auth” deletes the stored service secret.'),
                    if (_serviceAuthKind == 'SIGNED_JWT') ...[
                      YTextField(
                        label: 'Signing key ID',
                        wireKey: 'serviceJwtKid',
                        controller: _kid,
                        form: _form,
                        name: 'serviceJwtKid',
                        optional: true,
                        mono: true,
                        hint: 'e.g. yaya-in-1',
                        helper: 'Sent as the JWT’s kid header so your verifier can pick the key. Leave blank if it doesn’t select keys by kid.',
                      ),
                      YTextField(
                        label: 'Audience override',
                        wireKey: 'serviceJwtAudience',
                        controller: _audience,
                        form: _form,
                        name: 'serviceJwtAudience',
                        optional: true,
                        mono: true,
                        hint: 'e.g. yaya',
                        helper: 'The JWT’s aud. Blank = your tenant ID; set it only if your verifier expects something else.',
                      ),
                    ],
                    _number(_timeout, 'timeoutMs', 'Timeout', unit: 'ms', helper: 'How long to wait for your webhook, 100–30000 ms.'),
                  ],
                )
              else
                YFormSection(
                  title: 'Bean',
                  card: false,
                  children: [
                    if (beans.isEmpty && !_editing)
                      const YBanner(
                        tone: YBannerTone.warning,
                        message: 'This deployment registers no resolver beans. Use a webhook instead.',
                      )
                    else
                      YResourcePicker(
                        label: 'Resolver bean',
                        wireKey: 'beanName',
                        form: _form,
                        name: 'beanName',
                        required: true,
                        value: _beanName,
                        loading: data == null,
                        resources: [for (final b in beans) YResource(id: b)],
                        helper: 'The AuthResolver beans registered on this deployment.',
                        onChanged: (v) => setState(() => _beanName = v),
                      ),
                  ],
                ),
              const Divider(height: Gap.xl),
              YFormSection(
                title: 'Token cache',
                description: 'Caching skips the resolver for a caller whose token is still valid.',
                card: false,
                children: [
                  YSwitchField(
                    title: 'Cache tokens',
                    wireKey: 'cacheEnabled',
                    value: _cacheEnabled,
                    description: _cacheEnabled
                        ? 'Reuse a caller’s token until shortly before it expires.'
                        : 'Every conversation start calls the resolver — slower, and more load on your webhook.',
                    onChanged: (v) {
                      _form.changed('cacheEnabled');
                      setState(() => _cacheEnabled = v);
                    },
                  ),
                  _number(_refreshWindow, 'refreshWindowSeconds', 'Refresh window',
                      unit: 's', helper: 'Treat a token as expired this long before its exp, so it’s refreshed ahead of time. 0–600.'),
                  _number(_maxTokenAge, 'maxTokenAgeSeconds', 'Max token age',
                      unit: 's', helper: 'How long to keep a token that has no exp claim. 60–86400.'),
                  _number(_maxCache, 'maxCacheSeconds', 'Cache ceiling',
                      unit: 's',
                      optional: true,
                      hint: 'e.g. 900',
                      helper: _editing && _base!.maxCacheSeconds != null
                          ? 'Never cache longer than this, even if the token lives longer. Blank keeps the current ${_base!.maxCacheSeconds}s.'
                          : 'Never cache longer than this, even if the token lives longer — for long-lived tokens you still want re-checked.'),
                ],
              ),
              const Divider(height: Gap.xl),
              YEnumCards<String>(
                label: 'When a refresh fails',
                wireKey: 'onRefreshFailure',
                form: _form,
                name: 'onRefreshFailure',
                required: true,
                helper: 'If the resolver can’t refresh a caller’s token in the middle of a conversation.',
                value: _onRefreshFailure,
                onChanged: (v) => setState(() => _onRefreshFailure = v),
                options: const [
                  YEnumOption('REJECT', 'End the conversation', wire: 'REJECT',
                      description: 'Safest: the caller must start over and be resolved again.'),
                  YEnumOption('DOWNGRADE_TO_ANONYMOUS', 'Continue without identity', wire: 'DOWNGRADE_TO_ANONYMOUS', caution: true,
                      description: 'The conversation goes on as an anonymous caller; tools that need the identity are denied.'),
                ],
              ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save resolver' : 'Create resolver',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}

/// Runs the saved resolver once with a sentinel caller and shows what came
/// back: transport, timing, and how the token fared against the tenant's
/// trust roots.
Future<void> showAuthResolverProbe(BuildContext context, {required String tenant, required String resolver, bool unsaved = false}) {
  return showDialog<void>(
    context: context,
    builder: (_) => _ProbeDialog(tenant: tenant, resolver: resolver, unsaved: unsaved),
  );
}

class _ProbeDialog extends ConsumerStatefulWidget {
  const _ProbeDialog({required this.tenant, required this.resolver, required this.unsaved});
  final String tenant;
  final String resolver;
  final bool unsaved;

  @override
  ConsumerState<_ProbeDialog> createState() => _ProbeDialogState();
}

class _ProbeDialogState extends ConsumerState<_ProbeDialog> {
  final _caller = TextEditingController(text: '+15555550100');
  final _senderId = TextEditingController();
  final _senderPhone = TextEditingController();
  bool _running = false;
  AuthResolverProbeResult? _result;
  String? _error;

  @override
  void dispose() {
    for (final c in [_caller, _senderId, _senderPhone]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _run() async {
    setState(() {
      _running = true;
      _error = null;
    });
    try {
      final api = await ref.read(adminApiProvider.future);
      final r = await api.testAuthResolver(
        widget.tenant,
        widget.resolver,
        AuthResolverProbeInput(callerPhone: _caller.text.trim(), senderId: _senderId.text.trim(), senderPhone: _senderPhone.text.trim()),
      );
      if (mounted) setState(() => _result = r);
    } catch (e) {
      if (mounted) setState(() => _error = ApiErrorInfo.from(e).message);
    } finally {
      if (mounted) setState(() => _running = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Test ${widget.resolver}'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(
              'Calls the resolver now with a test caller and checks the token it returns against this tenant’s trust roots. '
              'Nothing is cached.',
              style: TextStyle(fontSize: 13, height: 1.45, color: scheme.onSurfaceVariant),
            ),
            if (widget.unsaved) ...[
              const SizedBox(height: Gap.md),
              const YBanner(tone: YBannerTone.warning, message: 'This tests the saved resolver — your unsaved edits aren’t included.'),
            ],
            const SizedBox(height: Gap.lg),
            YTextField(
              label: 'Caller phone',
              wireKey: 'callerPhone',
              controller: _caller,
              mono: true,
              hint: 'e.g. +18095551234',
              helper: 'E.164. A real customer’s phone shows what your webhook really returns.',
            ),
            const SizedBox(height: Gap.md),
            YTextField(
              label: 'Sender ID',
              wireKey: 'senderId',
              controller: _senderId,
              optional: true,
              mono: true,
              hint: 'snd_test_probe',
            ),
            const SizedBox(height: Gap.md),
            YTextField(
              label: 'Sender phone',
              wireKey: 'senderPhone',
              controller: _senderPhone,
              optional: true,
              mono: true,
              hint: '+15555550199',
            ),
            if (_error != null) ...[
              const SizedBox(height: Gap.lg),
              YBanner(tone: YBannerTone.error, title: 'The test couldn’t run.', message: _error!),
            ],
            if (_result != null) ...[
              const SizedBox(height: Gap.lg),
              _ProbeResultView(result: _result!),
            ],
          ]),
        ),
      ),
      actions: [
        OutlinedButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
        FilledButton.icon(
          onPressed: _running ? null : _run,
          icon: _running
              ? SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2, color: scheme.onPrimary))
              : const Icon(Icons.bolt_outlined, size: 18),
          label: Text(_running ? 'Running…' : _result == null ? 'Run test' : 'Run again'),
        ),
      ],
    );
  }
}

class _ProbeResultView extends StatelessWidget {
  const _ProbeResultView({required this.result});
  final AuthResolverProbeResult result;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final v = result.verification;
    final rows = <(String, String, bool)>[
      if ((result.url ?? '').isNotEmpty) ('URL', result.url!, true),
      if (result.elapsedMs != null) ('Elapsed', '${result.elapsedMs} ms', false),
      ('Token received', result.tokenReceived ? 'Yes' : 'No', false),
      if (result.httpStatus != null) ('HTTP status', '${result.httpStatus}', true),
      if (result.errorStatus != null) ('Error status', result.errorStatus!, true),
      if (result.errorMessage != null) ('Error', result.errorMessage!, false),
    ];
    final verification = v == null
        ? const <(String, String, bool)>[]
        : <(String, String, bool)>[
            if (v.reason != null) ('Reason', v.reason!, false),
            if (v.issuer != null) ('Issuer (iss)', v.issuer!, true),
            if (v.subject != null) ('Subject (sub)', v.subject!, true),
            if (v.audience.isNotEmpty) ('Audience (aud)', v.audience.join(', '), true),
            if (v.exp != null) ('Expires (exp)', v.exp!, true),
            if (v.kid != null) ('Key ID (kid)', v.kid!, true),
            if (v.profileId != null) ('Profile (_profile_id)', v.profileId!, true),
            if (v.scopes.isNotEmpty) ('Scopes', v.scopes.join(' '), true),
          ];
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(border: Border.all(color: scheme.outlineVariant), borderRadius: BorderRadius.circular(10)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          result.ok
              ? const YBadge('Passed', tone: YTone.success, icon: Icons.check_circle_outline)
              : const YBadge('Failed', tone: YTone.danger, icon: Icons.error_outline),
          const SizedBox(width: 8),
          YBadge(result.transport, mono: true),
        ]),
        const SizedBox(height: Gap.md),
        for (final r in rows) _kv(context, r),
        if (v != null) ...[
          const Divider(height: Gap.xl),
          Row(children: [
            Text('Token verification', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: scheme.onSurface)),
            const SizedBox(width: 8),
            v.ok
                ? const YBadge('Verified', tone: YTone.success, icon: Icons.verified_outlined)
                : const YBadge('Not verified', tone: YTone.danger, icon: Icons.gpp_bad_outlined),
          ]),
          const SizedBox(height: Gap.sm),
          for (final r in verification) _kv(context, r),
        ],
      ]),
    );
  }

  Widget _kv(BuildContext context, (String, String, bool) r) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 150, child: Text(r.$1, style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant))),
        Expanded(
          child: SelectableText(r.$2, style: r.$3 ? monoStyle(context, size: 13) : TextStyle(fontSize: 13, color: scheme.onSurface)),
        ),
      ]),
    );
  }
}
