import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/tenant_oidc_issuer.dart';
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
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';

final tenantOidcIssuersProvider = FutureProvider.family<List<TenantOidcIssuerResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.oidcIssuer);
  final api = await ref.watch(adminApiProvider.future);
  return api.listTenantOidcIssuers(tenantId);
});

const _labels = {
  'iss': 'Issuer',
  'jwksUri': 'JWKS URL',
  'audiences': 'Accepted audiences',
};

List<String> _lines(String text) => text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

/// The backend requires an absolute URL with a scheme and host; anything but
/// https is only sensible on a developer machine, so it is called out.
String? _absoluteUrl(String v, String what) {
  final t = v.trim();
  if (t.isEmpty) return 'Enter the $what.';
  final u = Uri.tryParse(t);
  if (u == null || !u.hasScheme || u.host.isEmpty) return 'Enter a full URL, e.g. https://login.example.com/';
  if (!u.isScheme('https') && !u.isScheme('http')) return 'Use an https:// URL.';
  return null;
}

bool _isLocal(Uri u) => u.host == 'localhost' || u.host == '127.0.0.1' || u.host.endsWith('.local') || u.host == 'host.docker.internal';

bool _insecure(String v) {
  final u = Uri.tryParse(v.trim());
  return u != null && u.isScheme('http') && u.host.isNotEmpty && !_isLocal(u);
}

String _host(String url) => Uri.tryParse(url)?.host.isNotEmpty == true ? Uri.parse(url).host : url;

/// Per-tenant OIDC issuer trust list read by `OidcAuthenticator`. Every write
/// evicts the authenticator's per-tenant cache server-side.
class OidcIssuersScreen extends ConsumerStatefulWidget {
  const OidcIssuersScreen({super.key});

  @override
  ConsumerState<OidcIssuersScreen> createState() => _OidcIssuersScreenState();
}

class _OidcIssuersScreenState extends ConsumerState<OidcIssuersScreen> {
  String? _handledLink;

  Future<void> _open(TenantOidcIssuerResponse? existing) async {
    await showYSheet<void>(
      context,
      label: existing == null ? 'Trust an OIDC issuer' : 'Edit OIDC issuer',
      builder: (_) => OidcIssuerEditorSheet(existing: existing),
    );
  }

  void _handleDeepLink(List<TenantOidcIssuerResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((r) => '${r.id}' == link || r.iss == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No OIDC issuer “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/oidc-issuers');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'OIDC issuers');
    }
    final issuers = ref.watch(tenantOidcIssuersProvider(tenant));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.oidcIssuer));
    issuers.whenData(_handleDeepLink);

    return YPage(
      title: 'OIDC issuers',
      subtitle: 'Identity providers whose RS256/ES256 tokens this tenant accepts. A token is trusted when its iss matches, '
          'its signature checks out against the issuer’s JWKS, and — if you list any — its aud is one of yours.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Trust an issuer')),
      ],
      child: LiveAsyncView<List<TenantOidcIssuerResponse>>(
        value: issuers,
        what: 'OIDC issuers',
        onRetry: () => ref.invalidate(tenantOidcIssuersProvider(tenant)),
        data: (context, list) => YTable<TenantOidcIssuerResponse>(
          rows: [...list]..sort((a, b) => a.iss.compareTo(b.iss)),
          noun: 'issuers',
          idOf: (r) => '${r.id}',
          searchHint: 'Search by issuer, JWKS URL or audience',
          searchTextOf: (r) => '${r.iss} ${r.jwksUri} ${r.audiences.join(' ')}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('Any audience', (r) => r.audiences.isEmpty),
            YFilter('Not https', (r) => !r.jwksUri.startsWith('https://') || !r.iss.startsWith('https://')),
          ],
          empty: YEmptyState(
            icon: Icons.vpn_key_outlined,
            title: 'No trusted issuers yet',
            message: 'Add your identity provider (Auth0, Keycloak, Entra…) so its signed-in users can talk to this tenant’s profiles. '
                'Until then, issuers in the server’s YAML config apply, if any.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Trust your first issuer')),
          ),
          columns: [
            YColumn(
              label: 'Issuer',
              flex: 4,
              sortKey: (r) => r.iss,
              cell: (context, r) => Text(r.iss, overflow: TextOverflow.ellipsis, style: monoStyle(context, weight: FontWeight.w500)),
            ),
            YColumn(
              label: 'JWKS',
              flex: 3,
              sortKey: (r) => r.jwksUri,
              cell: (context, r) => Tooltip(
                message: r.jwksUri,
                child: Text(_host(r.jwksUri), overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
              ),
            ),
            YColumn(
              label: 'Audiences',
              flex: 3,
              sortKey: (r) => r.audiences.length,
              cell: (context, r) => r.audiences.isEmpty
                  ? const YBadge('Any audience', tone: YTone.warning, icon: Icons.warning_amber_rounded)
                  : Text(r.audiences.join(', '), overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
            ),
            YColumn(
              label: 'Updated',
              width: 120,
              sortKey: (r) => r.updatedAt ?? r.createdAt ?? '',
              cell: (context, r) => Text(fresh.contains('${r.id}') ? 'Just now' : formatRelativeIso(r.updatedAt ?? r.createdAt)),
            ),
          ],
        ),
      ),
    );
  }
}

class OidcIssuerEditorSheet extends ConsumerStatefulWidget {
  const OidcIssuerEditorSheet({super.key, this.existing});
  final TenantOidcIssuerResponse? existing;

  @override
  ConsumerState<OidcIssuerEditorSheet> createState() => _OidcIssuerEditorSheetState();
}

class _OidcIssuerEditorSheetState extends ConsumerState<OidcIssuerEditorSheet> {
  final _iss = TextEditingController();
  final _jwks = TextEditingController();
  final _audiences = TextEditingController();
  late final YFormController _form;

  TenantOidcIssuerResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _iss.text = _base?.iss ?? '';
    _jwks.text = _base?.jwksUri ?? '';
    _audiences.text = (_base?.audiences ?? const []).join('\n');
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {'iss': _iss.text.trim(), 'jwksUri': _jwks.text.trim(), 'audiences': _lines(_audiences.text)},
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.oidcIssuer.$tenant.${_base?.id ?? 'new'}',
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      _iss.text = (d.values['iss'] as String?) ?? _iss.text;
      _jwks.text = (d.values['jwksUri'] as String?) ?? _jwks.text;
      final auds = d.values['audiences'];
      if (auds is List) _audiences.text = auds.join('\n');
      _form.refresh();
    });
  }

  void _rebuild() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _form.removeListener(_rebuild);
    _form.dispose();
    for (final c in [_iss, _jwks, _audiences]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, String?> _validate() {
    var issErr = _absoluteUrl(_iss.text, 'issuer URL');
    if (issErr == null) {
      final tenant = ref.read(currentTenantOrNull);
      final others = tenant == null ? const <TenantOidcIssuerResponse>[] : (ref.read(tenantOidcIssuersProvider(tenant)).valueOrNull ?? const []);
      if (others.any((r) => r.iss == _iss.text.trim() && r.id != _base?.id)) {
        issErr = 'This issuer is already trusted on this tenant. Open it instead.';
      }
    }
    return {
      'iss': issErr,
      'jwksUri': _absoluteUrl(_jwks.text, 'JWKS URL'),
    };
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = TenantOidcIssuerRequest(iss: _iss.text.trim(), jwksUri: _jwks.text.trim(), audiences: _lines(_audiences.text));
      final saved = _editing ? await api.updateTenantOidcIssuer(tenant, _base!.id, body) : await api.createTenantOidcIssuer(tenant, body);
      ref.invalidate(tenantOidcIssuersProvider(tenant));
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, _editing ? 'Saved ${saved.iss}' : 'Now trusting ${saved.iss}');
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
      title: 'Stop trusting ${_base!.iss}?',
      message: 'Tokens from this issuer stop being accepted for this tenant immediately. Users who sign in through it '
          'can’t start or continue conversations until you trust it again (unless the server’s YAML config also lists it).',
      confirmLabel: 'Stop trusting',
      destructive: true,
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteTenantOidcIssuer(tenant, _base!.id);
      ref.invalidate(tenantOidcIssuersProvider(tenant));
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'No longer trusting ${_base!.iss}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final noAudience = _lines(_audiences.text).isEmpty;
    final insecure = _insecure(_iss.text) || _insecure(_jwks.text);
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? 'OIDC issuer' : 'Trust an OIDC issuer',
          subtitle: _editing ? _base!.iss : 'Fields marked * are required.',
          headerActions: [
            if (_editing)
              TextButton.icon(
                onPressed: _form.saving ? null : _delete,
                icon: const Icon(Icons.delete_outline, size: 18),
                label: const Text('Remove'),
                style: TextButton.styleFrom(foregroundColor: Theme.of(context).colorScheme.error),
              ),
          ],
          body: ListView(
            padding: const EdgeInsets.fromLTRB(28, 20, 28, 28),
            children: [
              if (_form.summary.isNotEmpty || _form.formError != null) ...[
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'trusting this issuer'),
                const SizedBox(height: Gap.lg),
              ],
              const YBanner(
                tone: YBannerTone.info,
                title: 'What trusting an issuer means.',
                message: 'Any token this issuer signs with a key from its JWKS — with a matching iss and, if you list audiences, '
                    'a matching aud — is accepted as a verified user of this tenant. Its sub, scopes and claims then drive '
                    'authorization and identity routing.',
              ),
              const SizedBox(height: Gap.xl),
              YTextField(
                label: 'Issuer',
                wireKey: 'iss',
                controller: _iss,
                form: _form,
                name: 'iss',
                required: true,
                autofocus: true,
                mono: true,
                hint: 'e.g. https://acme.eu.auth0.com/',
                helper: 'Must equal the token’s iss claim exactly — including any trailing slash.',
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'JWKS URL',
                wireKey: 'jwksUri',
                controller: _jwks,
                form: _form,
                name: 'jwksUri',
                required: true,
                mono: true,
                hint: 'e.g. https://acme.eu.auth0.com/.well-known/jwks.json',
                helper: 'Where the issuer publishes its signing keys. Listed as jwks_uri in the issuer’s /.well-known/openid-configuration.',
                onChanged: (_) => setState(() {}),
              ),
              if (insecure) ...[
                const SizedBox(height: Gap.md),
                const YBanner(
                  tone: YBannerTone.warning,
                  message: 'This uses plain http:// outside a local machine. Keys fetched over http can be swapped in transit — use https.',
                ),
              ],
              const SizedBox(height: Gap.fieldGap),
              YTextField(
                label: 'Accepted audiences',
                wireKey: 'audiences',
                controller: _audiences,
                form: _form,
                name: 'audiences',
                optional: true,
                mono: true,
                minLines: 2,
                maxLines: 5,
                hint: 'https://api.acme.com\nacme-chat',
                helper: 'One per line. The token’s aud must include one of these.',
                onChanged: (_) => setState(() {}),
              ),
              if (noAudience) ...[
                const SizedBox(height: Gap.md),
                const YBanner(
                  tone: YBannerTone.warning,
                  message: 'No audience check: tokens this issuer minted for any of its other apps are accepted here too.',
                ),
              ],
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: _editing ? 'Save issuer' : 'Trust issuer',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
