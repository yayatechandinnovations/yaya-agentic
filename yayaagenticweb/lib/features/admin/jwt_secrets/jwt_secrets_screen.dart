import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../api/admin_api.dart';
import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/tenant_jwt_secret.dart';
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
import '../../../ui/form/y_key_value_field.dart';
import '../../../ui/tokens.dart';
import '../shared/admin_shared.dart';

final tenantJwtSecretsProvider = FutureProvider.family<List<TenantJwtSecretResponse>, String>((ref, tenantId) async {
  liveResource(ref, AdminResourceKind.jwtSecret);
  final api = await ref.watch(adminApiProvider.future);
  return api.listTenantJwtSecrets(tenantId);
});

const _labels = {
  'iss': 'Issuer',
  'secret': 'Signing secret',
  'audiences': 'Accepted audiences',
  'requiredClaims': 'Required claims',
  'principalKind': 'Caller kind',
};

List<String> _lines(String text) => text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

/// HS256 needs a key of at least 256 bits; the verifier refuses anything
/// shorter, so a short secret would be saved and then reject every token.
const _minSecretBytes = 32;

/// Per-tenant HMAC (HS256/384/512) JWT trust list — the path for an identity
/// service that doesn't publish a JWKS. The secret goes in on create/rotate
/// only; nothing reads it back.
class JwtSecretsScreen extends ConsumerStatefulWidget {
  const JwtSecretsScreen({super.key});

  @override
  ConsumerState<JwtSecretsScreen> createState() => _JwtSecretsScreenState();
}

class _JwtSecretsScreenState extends ConsumerState<JwtSecretsScreen> {
  String? _handledLink;

  Future<void> _open(TenantJwtSecretResponse? existing) async {
    await showYSheet<void>(
      context,
      label: existing == null ? 'New JWT secret' : 'Edit JWT secret',
      builder: (_) => JwtSecretEditorSheet(existing: existing),
    );
  }

  void _handleDeepLink(List<TenantJwtSecretResponse> list) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final match = link.isEmpty ? null : list.where((r) => '${r.id}' == link || r.iss == link).firstOrNull;
      if (link.isNotEmpty && match == null) {
        showToast(context, 'No JWT secret “$link” on this tenant', error: true);
      } else {
        await _open(match);
      }
      if (mounted) context.go('/admin/jwt-secrets');
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'JWT shared secrets');
    }
    final secrets = ref.watch(tenantJwtSecretsProvider(tenant));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.jwtSecret));
    secrets.whenData(_handleDeepLink);

    return YPage(
      title: 'JWT secrets',
      subtitle: 'Shared HMAC keys for identity services that sign HS256 tokens instead of publishing a JWKS. '
          'Secrets are encrypted at rest and never shown again after you save them.',
      actions: [
        FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('New secret')),
      ],
      child: LiveAsyncView<List<TenantJwtSecretResponse>>(
        value: secrets,
        what: 'JWT secrets',
        onRetry: () => ref.invalidate(tenantJwtSecretsProvider(tenant)),
        data: (context, list) => YTable<TenantJwtSecretResponse>(
          rows: [...list]..sort((a, b) => a.iss.compareTo(b.iss)),
          noun: 'secrets',
          idOf: (r) => '${r.id}',
          searchHint: 'Search by issuer, audience or caller kind',
          searchTextOf: (r) => '${r.iss} ${r.audiences.join(' ')} ${r.principalKind ?? ''} ${r.requiredClaims.keys.join(' ')}',
          highlightIds: fresh,
          onRowTap: _open,
          filters: [
            YFilter('Any audience', (r) => r.audiences.isEmpty),
            YFilter('Pinned to tenant', (r) => r.requiredClaims.isNotEmpty),
            YFilter('Grants a caller kind', (r) => r.principalKind != null),
          ],
          empty: YEmptyState(
            icon: Icons.key_outlined,
            title: 'No JWT secrets yet',
            message: 'Add one when your identity service signs HS256 tokens with a shared key. RS256/ES256 tokens from an IdP go under OIDC issuers.',
            action: FilledButton.icon(onPressed: () => _open(null), icon: const Icon(Icons.add, size: 18), label: const Text('Add your first secret')),
          ),
          columns: [
            YColumn(
              label: 'Issuer',
              flex: 4,
              sortKey: (r) => r.iss,
              cell: (context, r) => YCellStack(primary: r.iss, mono: true, secondary: 'Secret hidden'),
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
              label: 'Pinned to',
              flex: 3,
              sortKey: (r) => r.requiredClaims.length,
              cell: (context, r) => r.requiredClaims.isEmpty
                  ? Text('No claim pins', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
                  : Text(r.requiredClaims.entries.map((e) => '${e.key}=${e.value}').join(', '),
                      overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
            ),
            YColumn(
              label: 'Caller kind',
              width: 170,
              sortKey: (r) => r.principalKind ?? '',
              cell: (context, r) => Text(r.principalKind ?? '—', overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
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

class JwtSecretEditorSheet extends ConsumerStatefulWidget {
  const JwtSecretEditorSheet({super.key, this.existing});
  final TenantJwtSecretResponse? existing;

  @override
  ConsumerState<JwtSecretEditorSheet> createState() => _JwtSecretEditorSheetState();
}

class _JwtSecretEditorSheetState extends ConsumerState<JwtSecretEditorSheet> {
  final _iss = TextEditingController();
  final _secret = TextEditingController();
  final _audiences = TextEditingController();
  final _kind = TextEditingController();
  Map<String, String> _claims = {};
  late final YFormController _form;

  TenantJwtSecretResponse? get _base => widget.existing;
  bool get _editing => _base != null;

  @override
  void initState() {
    super.initState();
    _iss.text = _base?.iss ?? '';
    _audiences.text = (_base?.audiences ?? const []).join('\n');
    _kind.text = _base?.principalKind ?? '';
    _claims = Map.of(_base?.requiredClaims ?? const {});
    final tenant = ref.read(currentTenantOrNull);
    _form = YFormController(
      snapshot: () => {
        'iss': _iss.text.trim(),
        'secret': _secret.text,
        'audiences': _lines(_audiences.text),
        'requiredClaims': _claims,
        'principalKind': _kind.text.trim(),
      },
      validate: _validate,
      labels: _labels,
      draftKey: 'yaya.draft.jwtSecret.$tenant.${_base?.id ?? 'new'}',
      excludeFromDraft: const {'secret'},
    );
    _form.addListener(_rebuild);
    _form.loadDraft().then((d) {
      if (d == null || !mounted) return;
      final v = d.values;
      _iss.text = (v['iss'] as String?) ?? _iss.text;
      _kind.text = (v['principalKind'] as String?) ?? _kind.text;
      final auds = v['audiences'];
      if (auds is List) _audiences.text = auds.join('\n');
      final claims = v['requiredClaims'];
      if (claims is Map) _claims = claims.map((k, v) => MapEntry('$k', '$v'));
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
    for (final c in [_iss, _secret, _audiences, _kind]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, String?> _validate() {
    var issErr = Validators.required(_iss.text, 'the issuer (the token’s iss claim)');
    if (issErr == null) {
      final tenant = ref.read(currentTenantOrNull);
      final others = tenant == null ? const <TenantJwtSecretResponse>[] : (ref.read(tenantJwtSecretsProvider(tenant)).valueOrNull ?? const []);
      if (others.any((r) => r.iss == _iss.text.trim() && r.id != _base?.id)) {
        issErr = 'This issuer already has a secret on this tenant. Open it to rotate the secret.';
      }
    }
    final secret = _secret.text.trim();
    String? secretErr;
    if (secret.isEmpty) {
      if (!_editing) secretErr = 'Enter the shared signing secret.';
    } else if (utf8.encode(secret).length < _minSecretBytes) {
      secretErr = 'Use at least $_minSecretBytes characters — HS256 rejects shorter keys, so every token would fail.';
    }
    final blankClaim = _claims.entries.where((e) => e.value.trim().isEmpty).map((e) => e.key).toList();
    return {
      'iss': issErr,
      'secret': secretErr,
      'requiredClaims': blankClaim.isEmpty ? null : 'Give ${blankClaim.join(', ')} a value, or remove ${blankClaim.length == 1 ? 'it' : 'them'}.',
      'principalKind': _kind.text.trim().isNotEmpty && _claims.isEmpty
          ? 'A caller kind needs at least one required claim above, so only this tenant’s tokens can carry it.'
          : null,
    };
  }

  Future<void> _save() async {
    if (!_form.submit()) return;
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final rotating = _editing && _secret.text.trim().isNotEmpty;
    if (rotating) {
      final ok = await confirmTyped(
        context,
        title: 'Rotate the secret for ${_base!.iss}?',
        message: 'Tokens signed with the current secret stop working as soon as you save. '
            'Users of that identity service are rejected until it signs with the new secret.',
        expected: _base!.iss,
        confirmLabel: 'Rotate secret',
      );
      if (!ok) return;
    }
    _form.saving = true;
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = TenantJwtSecretRequest(
        iss: _iss.text.trim(),
        secret: _secret.text.trim().isEmpty ? null : _secret.text.trim(),
        audiences: _lines(_audiences.text),
        principalKind: _kind.text.trim(),
        requiredClaims: {for (final e in _claims.entries) e.key: e.value.trim()},
      );
      final saved = _editing ? await api.updateTenantJwtSecret(tenant, _base!.id, body) : await api.createTenantJwtSecret(tenant, body);
      ref.invalidate(tenantJwtSecretsProvider(tenant));
      _secret.clear();
      _form.saving = false;
      _form.markClean();
      if (!mounted) return;
      showToast(context, !_editing ? 'Added secret for ${saved.iss}' : rotating ? 'Rotated secret for ${saved.iss}' : 'Saved ${saved.iss}');
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
      title: 'Delete the JWT secret for ${_base!.iss}?',
      message: 'Tokens signed with it stop working immediately — users of that identity service can’t start or continue '
          'conversations on this tenant. The secret can’t be recovered.',
      expected: _base!.iss,
      confirmLabel: 'Delete secret',
    );
    if (!ok) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteTenantJwtSecret(tenant, _base!.id);
      ref.invalidate(tenantJwtSecretsProvider(tenant));
      _form.markClean();
      if (!mounted) return;
      showToast(context, 'Deleted the secret for ${_base!.iss}');
      Navigator.of(context).pop();
    } catch (e) {
      _form.applyServerError(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final noAudience = _lines(_audiences.text).isEmpty;
    return YUnsavedGuard(
      form: _form,
      child: YSaveShortcut(
        onSave: _save,
        child: YSheetScaffold(
          title: _editing ? 'JWT secret' : 'New JWT secret',
          subtitle: _editing ? _base!.iss : 'Fields marked * are required.',
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
                YErrorSummary(form: _form, action: _editing ? 'saving' : 'adding this secret'),
                const SizedBox(height: Gap.lg),
              ],
              YTextField(
                label: 'Issuer',
                wireKey: 'iss',
                controller: _iss,
                form: _form,
                name: 'iss',
                required: true,
                autofocus: !_editing,
                mono: true,
                hint: 'e.g. smart-lottery-identity',
                helper: 'Must equal the token’s iss claim exactly. Tokens with this iss are checked against the secret below.',
              ),
              const SizedBox(height: Gap.fieldGap),
              YSecretField(
                label: _editing ? 'New signing secret' : 'Signing secret',
                wireKey: 'secret',
                controller: _secret,
                form: _form,
                name: 'secret',
                required: true,
                stored: _editing,
                helper: _editing
                    ? 'Stored and hidden. Leave blank to keep it; entering a value rotates it when you save.'
                    : 'The HMAC key your identity service signs with — at least $_minSecretBytes characters. Stored encrypted; you won’t see it again.',
              ),
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
                hint: 'yaya-agentic',
                helper: 'One per line. The token’s aud must include one of these.',
                onChanged: (_) => setState(() {}),
              ),
              if (noAudience) ...[
                const SizedBox(height: Gap.md),
                const YBanner(
                  tone: YBannerTone.warning,
                  message: 'No audience check: any token this issuer signs with the secret is accepted, whatever app it was meant for.',
                ),
              ],
              const SizedBox(height: Gap.xl),
              YFormSection(
                title: 'Pin to this tenant',
                description: 'When several tenants share one signing key, the key proves the issuer — not the tenant. '
                    'Required claims bind a token to this tenant; a caller kind labels the users this issuer vouches for.',
                card: false,
                children: [
                  YKeyValueField(
                    label: 'Required claims',
                    wireKey: 'requiredClaims',
                    form: _form,
                    name: 'requiredClaims',
                    value: _claims,
                    keyHint: 'Claim, e.g. org',
                    valueHint: 'Exact value, e.g. acme-retail',
                    addLabel: 'Add claim',
                    helper: 'Every token must carry each claim with exactly this value, or it’s rejected.',
                    onChanged: (m) => setState(() => _claims = m),
                  ),
                  YTextField(
                    label: 'Caller kind',
                    wireKey: 'principalKind',
                    controller: _kind,
                    form: _form,
                    name: 'principalKind',
                    optional: true,
                    mono: true,
                    hint: 'e.g. network-operator',
                    helper: 'Profiles that list kinds in metadata.principalKinds admit only callers whose issuer grants one. Leave blank to grant none.',
                  ),
                ],
              ),
            ],
          ),
          footer: YActionBar(
            form: _form,
            primaryLabel: !_editing
                ? 'Add secret'
                : _secret.text.trim().isNotEmpty
                    ? 'Rotate secret'
                    : 'Save changes',
            onPrimary: _save,
            onCancel: () => closeYSheet(context),
          ),
        ),
      ),
    );
  }
}
