import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/tenant_jwt_secret.dart';
import '../shared/admin_shared.dart';

/// Refetched after every write so the list reflects the latest trust state.
final tenantJwtSecretsProvider =
    FutureProvider.family<List<TenantJwtSecretResponse>, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listTenantJwtSecrets(tenantId);
  },
);

/// Per-tenant HMAC-signed JWT trust list. The path for tenants whose
/// identity service mints HS256 tokens (typical internal-service shape)
/// rather than OIDC RS256/JWKS. Plaintext secrets only flow in on
/// create/rotate; the list and edit views never display the secret value.
class JwtSecretsScreen extends ConsumerStatefulWidget {
  const JwtSecretsScreen({super.key});

  @override
  ConsumerState<JwtSecretsScreen> createState() => _JwtSecretsScreenState();
}

class _JwtSecretsScreenState extends ConsumerState<JwtSecretsScreen> {
  final _issCtrl = TextEditingController();
  final _secretCtrl = TextEditingController();
  final _audiencesCtrl = TextEditingController(); // one audience per line
  int? _editingId;
  bool _showSecret = false;
  bool _saving = false;

  void _resetForm() {
    setState(() {
      _editingId = null;
      _issCtrl.clear();
      _secretCtrl.clear();
      _audiencesCtrl.clear();
      _showSecret = false;
    });
  }

  void _loadIntoForm(TenantJwtSecretResponse row) {
    setState(() {
      _editingId = row.id;
      _issCtrl.text = row.iss;
      _audiencesCtrl.text = row.audiences.join('\n');
      _secretCtrl.clear(); // intentional — operator must re-enter to rotate
      _showSecret = false;
    });
  }

  @override
  void dispose() {
    _issCtrl.dispose();
    _secretCtrl.dispose();
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
    if (_issCtrl.text.trim().isEmpty) {
      showSnack(context, 'iss is required', error: true);
      return;
    }
    if (_editingId == null && _secretCtrl.text.trim().isEmpty) {
      showSnack(context, 'secret is required on create', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = TenantJwtSecretRequest(
        iss: _issCtrl.text.trim(),
        secret: _secretCtrl.text.isEmpty ? null : _secretCtrl.text,
        audiences: _audiencesFromForm(),
      );
      if (_editingId == null) {
        await api.createTenantJwtSecret(tenant, body);
      } else {
        await api.updateTenantJwtSecret(tenant, _editingId!, body);
      }
      ref.invalidate(tenantJwtSecretsProvider(tenant));
      if (!mounted) return;
      final wasEdit = _editingId != null;
      final rotated = wasEdit && _secretCtrl.text.isNotEmpty;
      _resetForm();
      showSnack(
          context,
          wasEdit
              ? (rotated ? 'secret rotated' : 'metadata updated')
              : 'secret added');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(TenantJwtSecretResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove trusted JWT secret?'),
            content: Text(
                'HS256 tokens from iss "${row.iss}" will no longer be accepted for this tenant.'),
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
      await api.deleteTenantJwtSecret(tenant, row.id);
      ref.invalidate(tenantJwtSecretsProvider(tenant));
      if (mounted) showSnack(context, 'secret removed');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'JWT shared secrets');
    }
    final secrets = ref.watch(tenantJwtSecretsProvider(tenant));

    return AdminTwoPane(
      title: 'JWT shared secrets (HS256)',
      subtitle:
          'HMAC-signed JWTs for tenant "$tenant". The path for an internal identity service that '
          'doesn\'t expose JWKS. Secrets are AES-256-GCM-encrypted at rest and never displayed.',
      list: secrets.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(
                child: Text(
                    'No HS256 secrets yet. RS256/ES256 tokens are handled by OIDC issuers.'))
            : ListView.separated(
                itemBuilder: (_, i) => _SecretTile(
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
          title: _editingId == null ? 'Add JWT secret' : 'Editing secret #$_editingId',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _issCtrl,
                decoration: const InputDecoration(
                  labelText: 'iss',
                  helperText:
                      'Exact match against the JWT\'s iss claim (e.g. "smart-lottery-identity").',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _secretCtrl,
                obscureText: !_showSecret,
                decoration: InputDecoration(
                  labelText: _editingId == null
                      ? 'secret (HMAC key)'
                      : 'new secret (leave blank to keep current)',
                  helperText:
                      'Shared with the identity service. Stored AES-GCM-encrypted; never displayed after save.',
                  suffixIcon: IconButton(
                    icon: Icon(_showSecret
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined),
                    onPressed: () => setState(() => _showSecret = !_showSecret),
                    tooltip: _showSecret ? 'Hide' : 'Show',
                  ),
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

class _SecretTile extends StatelessWidget {
  const _SecretTile({
    required this.row,
    required this.onEdit,
    required this.onDelete,
  });

  final TenantJwtSecretResponse row;
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
          const Text('HS256 · secret hidden',
              style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'iss', value: row.iss, mono: true),
          DetailRow(
            label: 'audiences',
            value: row.audiences.isEmpty
                ? '(no aud check)'
                : row.audiences.join(', '),
          ),
          if (row.createdAt != null)
            DetailRow(label: 'createdAt', value: row.createdAt!),
          if (row.updatedAt != null)
            DetailRow(label: 'updatedAt', value: row.updatedAt!),
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
