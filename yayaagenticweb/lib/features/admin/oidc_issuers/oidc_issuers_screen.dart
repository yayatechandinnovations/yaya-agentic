import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/tenant_oidc_issuer.dart';
import '../shared/admin_shared.dart';

/// Refetched whenever a row is created / updated / deleted via this screen.
final tenantOidcIssuersProvider =
    FutureProvider.family<List<TenantOidcIssuerResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listTenantOidcIssuers(tenantId);
  },
);

/// Per-tenant OIDC issuer trust list — first-class config consumed by
/// `OidcAuthenticator`. Each row tells the runtime "for this tenant, trust
/// JWTs whose `iss` matches; verify against `jwksUri`; if `audiences` is
/// non-empty, require a match." Every write evicts the authenticator's
/// per-tenant processor cache server-side.
class OidcIssuersScreen extends ConsumerStatefulWidget {
  const OidcIssuersScreen({super.key});

  @override
  ConsumerState<OidcIssuersScreen> createState() => _OidcIssuersScreenState();
}

class _OidcIssuersScreenState extends ConsumerState<OidcIssuersScreen> {
  final _issCtrl = TextEditingController();
  final _jwksCtrl = TextEditingController();
  final _audiencesCtrl = TextEditingController(); // one audience per line
  int? _editingId;                                 // null = create mode
  bool _saving = false;

  void _resetForm() {
    setState(() {
      _editingId = null;
      _issCtrl.clear();
      _jwksCtrl.clear();
      _audiencesCtrl.clear();
    });
  }

  void _loadIntoForm(TenantOidcIssuerResponse row) {
    setState(() {
      _editingId = row.id;
      _issCtrl.text = row.iss;
      _jwksCtrl.text = row.jwksUri;
      _audiencesCtrl.text = row.audiences.join('\n');
    });
  }

  @override
  void dispose() {
    _issCtrl.dispose();
    _jwksCtrl.dispose();
    _audiencesCtrl.dispose();
    super.dispose();
  }

  List<String> _audiencesFromForm() => _audiencesCtrl.text
      .split('\n')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .toList();

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    if (_issCtrl.text.trim().isEmpty || _jwksCtrl.text.trim().isEmpty) {
      showSnack(context, 'iss and jwksUri are required', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = TenantOidcIssuerRequest(
        iss: _issCtrl.text.trim(),
        jwksUri: _jwksCtrl.text.trim(),
        audiences: _audiencesFromForm(),
      );
      if (_editingId == null) {
        await api.createTenantOidcIssuer(tenant, body);
      } else {
        await api.updateTenantOidcIssuer(tenant, _editingId!, body);
      }
      ref.invalidate(tenantOidcIssuersProvider(tenant));
      if (!mounted) return;
      final wasEdit = _editingId != null;
      _resetForm();
      showSnack(context, wasEdit ? 'issuer updated' : 'issuer added');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(TenantOidcIssuerResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove trusted issuer?'),
            content: Text(
                'JWTs from "${row.iss}" will no longer be accepted for this tenant.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Remove')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      await api.deleteTenantOidcIssuer(tenant, row.id);
      ref.invalidate(tenantOidcIssuersProvider(tenant));
      if (mounted) showSnack(context, 'issuer removed');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'OIDC issuers');
    }
    final issuers = ref.watch(tenantOidcIssuersProvider(tenant));

    return AdminTwoPane(
      title: 'OIDC issuers',
      subtitle:
          'Trusted JWT issuers for tenant "$tenant". Every write evicts the OidcAuthenticator '
          'cache; YAML at yaya.agentic.auth.oidc.issuers is kept as a fallback.',
      list: issuers.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(
                child: Text(
                    'No per-tenant issuers yet — falling back to YAML config if present.'))
            : ListView.separated(
                itemBuilder: (_, i) => _IssuerTile(
                  row: list[i],
                  onEdit: () => _loadIntoForm(list[i]),
                  onDelete: () => _delete(list[i]),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(
        child: FormCard(
          title: _editingId == null ? 'Add issuer' : 'Editing issuer #$_editingId',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _issCtrl,
                decoration: const InputDecoration(
                  labelText: 'iss',
                  helperText: 'Exact match against the JWT\'s iss claim (incl. trailing slash).',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _jwksCtrl,
                decoration: const InputDecoration(
                  labelText: 'jwksUri',
                  helperText: 'URL of the JWKS endpoint (usually <iss>/.well-known/jwks.json).',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _audiencesCtrl,
                minLines: 2,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: 'audiences (one per line, optional)',
                  helperText:
                      'Empty = no audience check. Otherwise the JWT\'s aud must include one of these.',
                ),
              ),
            ],
          ),
          actions: [
            if (_editingId != null)
              TextButton(
                onPressed: _saving ? null : _resetForm,
                child: const Text('Cancel'),
              ),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox.square(
                      dimension: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(_editingId == null ? 'Add' : 'Save'),
            ),
          ],
        ),
      ),
    );
  }
}

class _IssuerTile extends StatelessWidget {
  const _IssuerTile({
    required this.row,
    required this.onEdit,
    required this.onDelete,
  });

  final TenantOidcIssuerResponse row;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return EditableRecordCard(
      onCloneEdit: onEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(row.iss, style: Theme.of(context).textTheme.titleSmall),
          Text('jwks: ${row.jwksUri}', style: const TextStyle(fontSize: 12)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'iss', value: row.iss, mono: true),
          DetailRow(label: 'jwksUri', value: row.jwksUri, mono: true),
          DetailRow(
            label: 'audiences',
            value: row.audiences.isEmpty
                ? '(no aud check)'
                : row.audiences.join(', '),
          ),
          if (row.createdAt != null)
            DetailRow(label: 'createdAt', value: row.createdAt!),
        ],
      ),
      extraActions: [
        TextButton.icon(
          onPressed: onDelete,
          icon: const Icon(Icons.delete_outline, size: 18),
          label: const Text('Remove'),
          style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error),
        ),
      ],
    );
  }
}
