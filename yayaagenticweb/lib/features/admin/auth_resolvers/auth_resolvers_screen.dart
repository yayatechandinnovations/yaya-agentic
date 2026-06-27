import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../app/selected_tenant.dart';
import '../../../models/admin/auth_resolver.dart';
import '../shared/admin_shared.dart';

/// Refetched after every write so the binding form's dropdown reflects
/// the latest list immediately.
final authResolversProvider =
    FutureProvider.family<AuthResolverListResponse, String>(
  (ref, tenantId) async {
    final api = await ref.watch(adminApiProvider.future);
    return api.listAuthResolvers(tenantId);
  },
);

/// Per-tenant CRUD for `channel_auth_resolvers` rows — the identity
/// webhooks (or in-process beans) that turn a verified inbound channel
/// caller into a tenant-side JWT. Mirrors the JWT-secrets / OIDC-issuers
/// admin shape: list + edit form + secret-mask + test probe button.
class AuthResolversScreen extends ConsumerStatefulWidget {
  const AuthResolversScreen({super.key});

  @override
  ConsumerState<AuthResolversScreen> createState() =>
      _AuthResolversScreenState();
}

class _AuthResolversScreenState extends ConsumerState<AuthResolversScreen> {
  final _nameCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _beanNameCtrl = TextEditingController();
  final _httpPathCtrl = TextEditingController();
  final _serviceSecretCtrl = TextEditingController();
  final _timeoutCtrl = TextEditingController(text: '3000');
  final _refreshWindowCtrl = TextEditingController(text: '60');
  final _maxTokenAgeCtrl = TextEditingController(text: '3600');
  final _maxCacheSecondsCtrl = TextEditingController();

  String _kind = 'HTTP';
  String _serviceAuthKind = 'NONE';
  String _onRefreshFailure = 'REJECT';
  String _status = 'ACTIVE';
  bool _cacheEnabled = true;
  bool _showSecret = false;

  int? _editingId;        // null = create mode
  String? _editingName;   // PUT path uses the original name
  bool _saving = false;
  AuthResolverProbeResult? _lastProbe;

  void _resetForm() {
    setState(() {
      _editingId = null;
      _editingName = null;
      _nameCtrl.clear();
      _descCtrl.clear();
      _beanNameCtrl.clear();
      _httpPathCtrl.clear();
      _serviceSecretCtrl.clear();
      _timeoutCtrl.text = '3000';
      _refreshWindowCtrl.text = '60';
      _maxTokenAgeCtrl.text = '3600';
      _maxCacheSecondsCtrl.clear();
      _kind = 'HTTP';
      _serviceAuthKind = 'NONE';
      _onRefreshFailure = 'REJECT';
      _status = 'ACTIVE';
      _cacheEnabled = true;
      _showSecret = false;
      _lastProbe = null;
    });
  }

  void _loadIntoForm(AuthResolverResponse row) {
    setState(() {
      _editingId = row.id;
      _editingName = row.name;
      _nameCtrl.text = row.name;
      _descCtrl.text = row.description ?? '';
      _beanNameCtrl.text = row.beanName ?? '';
      _httpPathCtrl.text = row.httpPath ?? '';
      _serviceSecretCtrl.clear();   // operator must re-enter to rotate
      _timeoutCtrl.text = (row.timeoutMs ?? 3000).toString();
      _refreshWindowCtrl.text = (row.refreshWindowSeconds ?? 60).toString();
      _maxTokenAgeCtrl.text = (row.maxTokenAgeSeconds ?? 3600).toString();
      _maxCacheSecondsCtrl.text = row.maxCacheSeconds?.toString() ?? '';
      _kind = row.kind;
      _serviceAuthKind = row.serviceAuthKind ?? 'NONE';
      _onRefreshFailure = row.onRefreshFailure ?? 'REJECT';
      _status = row.status;
      _cacheEnabled = row.cacheEnabled ?? true;
      _showSecret = false;
      _lastProbe = null;
    });
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descCtrl.dispose();
    _beanNameCtrl.dispose();
    _httpPathCtrl.dispose();
    _serviceSecretCtrl.dispose();
    _timeoutCtrl.dispose();
    _refreshWindowCtrl.dispose();
    _maxTokenAgeCtrl.dispose();
    _maxCacheSecondsCtrl.dispose();
    super.dispose();
  }

  String? _validate() {
    if (_nameCtrl.text.trim().isEmpty) return 'Name is required.';
    if (_kind == 'BEAN' && _beanNameCtrl.text.trim().isEmpty) {
      return 'Bean name is required for kind=BEAN.';
    }
    if (_kind == 'HTTP') {
      final path = _httpPathCtrl.text.trim();
      if (path.isEmpty) return 'HTTP path is required for kind=HTTP.';
      if (!path.startsWith('/')) return "HTTP path must start with '/'.";
      if (path.contains('://') || path.contains('?') || path.contains('#')) {
        return "HTTP path must not contain '://', '?', or '#'.";
      }
      if (_editingId == null
          && (_serviceAuthKind == 'BEARER' || _serviceAuthKind == 'SIGNED_JWT')
          && _serviceSecretCtrl.text.trim().isEmpty) {
        return 'Service secret is required for $_serviceAuthKind on create.';
      }
    }
    return null;
  }

  AuthResolverRequest _buildRequest() {
    return AuthResolverRequest(
      name: _nameCtrl.text.trim(),
      description: _descCtrl.text.trim(),
      kind: _kind,
      beanName: _kind == 'BEAN' ? _beanNameCtrl.text.trim() : null,
      httpPath: _kind == 'HTTP' ? _httpPathCtrl.text.trim() : null,
      serviceAuthKind: _kind == 'HTTP' ? _serviceAuthKind : null,
      serviceSecret: _serviceSecretCtrl.text.isEmpty
          ? null
          : _serviceSecretCtrl.text,
      timeoutMs: _kind == 'HTTP'
          ? int.tryParse(_timeoutCtrl.text.trim()) ?? 3000
          : null,
      cacheEnabled: _cacheEnabled,
      maxCacheSeconds: _maxCacheSecondsCtrl.text.trim().isEmpty
          ? null
          : int.tryParse(_maxCacheSecondsCtrl.text.trim()),
      refreshWindowSeconds: int.tryParse(_refreshWindowCtrl.text.trim()) ?? 60,
      maxTokenAgeSeconds: int.tryParse(_maxTokenAgeCtrl.text.trim()) ?? 3600,
      onRefreshFailure: _onRefreshFailure,
      status: _status,
    );
  }

  Future<void> _save() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) {
      showSnack(context, 'select a tenant first', error: true);
      return;
    }
    final err = _validate();
    if (err != null) { showSnack(context, err, error: true); return; }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final body = _buildRequest();
      if (_editingId == null) {
        await api.createAuthResolver(tenant, body);
      } else {
        await api.updateAuthResolver(tenant, _editingName!, body);
      }
      ref.invalidate(authResolversProvider(tenant));
      if (!mounted) return;
      final wasEdit = _editingId != null;
      final rotated = wasEdit && _serviceSecretCtrl.text.isNotEmpty;
      _resetForm();
      showSnack(context,
          wasEdit ? (rotated ? 'resolver saved, secret rotated' : 'resolver saved') : 'resolver added');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _delete(AuthResolverResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Remove this auth resolver?'),
            content: Text(
                'Bindings pointing at "${row.name}" will fall through to '
                'their on_unresolved policy on the next inbound. Cached '
                'tokens for this tenant will be evicted.'),
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
      final resp = await api.deleteAuthResolver(tenant, row.name);
      ref.invalidate(authResolversProvider(tenant));
      if (mounted) {
        showSnack(context,
            'resolver removed (${resp.cacheEntriesEvicted} cached tokens evicted)');
      }
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _test(AuthResolverResponse row) async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final input = await showDialog<AuthResolverProbeInput?>(
      context: context,
      builder: (_) => const _ProbeInputDialog(),
    );
    if (input == null) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final result = await api.testAuthResolver(tenant, row.name, input);
      if (mounted) setState(() => _lastProbe = result);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _invalidateAllForTenant() async {
    final tenant = ref.read(currentTenantOrNull);
    if (tenant == null) return;
    final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Invalidate all cached tokens for this tenant?'),
            content: const Text(
                'Every cached JWT under auth-resolver-token:<tenant>:* will '
                'be removed. The next inbound message for any caller will '
                're-dispatch the resolver. Use after rotating a secret.'),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel')),
              FilledButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Invalidate')),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return;
    try {
      final api = await ref.read(adminApiProvider.future);
      final resp = await api.invalidateAuthResolverCache(tenant,
          AuthResolverInvalidateRequest(allForTenant: true));
      if (mounted) {
        showSnack(context, '${resp.count} cached tokens evicted');
      }
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(currentTenantOrNull);
    if (tenant == null) {
      return const TenantScopedEmptyState(resourceLabel: 'Auth resolvers');
    }
    final resolvers = ref.watch(authResolversProvider(tenant));

    return AdminTwoPane(
      title: 'Auth resolvers',
      subtitle:
          'Identity webhooks (or in-process beans) for tenant "$tenant". The '
          'resolver turns a verified inbound caller (e.g. WhatsApp phone) '
          "into a tenant-issued JWT — yaya-agentic doesn't run any tenant "
          'identity logic itself.',
      list: resolvers.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (data) {
          if (data.resolvers.isEmpty) {
            return const Center(
                child: Text(
                    'No resolvers yet. Bindings without one fall back to '
                    'baseline channel auth (anonymous WhatsApp principal).'));
          }
          return Column(
            children: [
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: _invalidateAllForTenant,
                  icon: const Icon(Icons.cleaning_services_outlined, size: 16),
                  label: const Text('Invalidate cached tokens'),
                ),
              ),
              Expanded(
                child: ListView.separated(
                  itemBuilder: (_, i) => _ResolverTile(
                    row: data.resolvers[i],
                    onEdit: () => _loadIntoForm(data.resolvers[i]),
                    onDelete: () => _delete(data.resolvers[i]),
                    onTest: () => _test(data.resolvers[i]),
                  ),
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemCount: data.resolvers.length,
                ),
              ),
            ],
          );
        },
      ),
      form: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            FormCard(
              title: _editingId == null
                  ? 'Add resolver'
                  : 'Editing resolver #$_editingId',
              child: resolvers.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(formatError(e),
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                data: (data) => _buildForm(data.registeredBeanNames),
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
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_editingId == null ? 'Add' : 'Save'),
                ),
              ],
            ),
            if (_lastProbe != null) ...[
              const SizedBox(height: 12),
              _ProbeResultCard(result: _lastProbe!),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildForm(List<String> registeredBeans) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _nameCtrl,
          decoration: const InputDecoration(
            labelText: 'Name',
            helperText:
                'Stable identifier — e.g. acme-customer-lookup. Bindings point at this by name.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _descCtrl,
          decoration: const InputDecoration(
            labelText: 'Description (optional)',
          ),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _kind,
          decoration: const InputDecoration(labelText: 'Kind'),
          items: const [
            DropdownMenuItem(value: 'HTTP', child: Text('HTTP (webhook)')),
            DropdownMenuItem(value: 'BEAN', child: Text('BEAN (in-process)')),
          ],
          onChanged: _editingId != null
              ? null      // kind is immutable after create
              : (v) => setState(() => _kind = v ?? 'HTTP'),
        ),
        const SizedBox(height: 12),
        if (_kind == 'BEAN')
          _beanSection(registeredBeans)
        else
          _httpSection(),
        const SizedBox(height: 12),
        _cacheSection(),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _onRefreshFailure,
          decoration: const InputDecoration(
            labelText: 'On refresh failure',
            helperText:
                'What to do on a tool-call refresh failure (mid-session).',
          ),
          items: const [
            DropdownMenuItem(value: 'REJECT', child: Text('REJECT — end session')),
            DropdownMenuItem(
                value: 'DOWNGRADE_TO_ANONYMOUS',
                child: Text('DOWNGRADE_TO_ANONYMOUS — continue without identity')),
          ],
          onChanged: (v) =>
              setState(() => _onRefreshFailure = v ?? 'REJECT'),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _status,
          decoration: const InputDecoration(labelText: 'Status'),
          items: const [
            DropdownMenuItem(value: 'ACTIVE', child: Text('Active')),
            DropdownMenuItem(
                value: 'DEACTIVATED', child: Text('Deactivated')),
          ],
          onChanged: (v) => setState(() => _status = v ?? 'ACTIVE'),
        ),
      ],
    );
  }

  Widget _beanSection(List<String> registeredBeans) {
    if (registeredBeans.isEmpty) {
      return Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.errorContainer,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
            'No AuthResolver beans registered on this deployment. SaaS '
            "deployments use kind=HTTP instead — switch the form above.",
            style: TextStyle(
                color: Theme.of(context).colorScheme.onErrorContainer)),
      );
    }
    final selected = registeredBeans.contains(_beanNameCtrl.text.trim())
        ? _beanNameCtrl.text.trim()
        : null;
    return DropdownButtonFormField<String>(
      initialValue: selected,
      decoration: const InputDecoration(labelText: 'Bean name'),
      items: registeredBeans
          .map((n) => DropdownMenuItem(value: n, child: Text(n)))
          .toList(),
      onChanged: (v) => setState(() => _beanNameCtrl.text = v ?? ''),
    );
  }

  Widget _httpSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _httpPathCtrl,
          decoration: const InputDecoration(
            labelText: 'HTTP path',
            helperText: 'Composed with tenant host_base_url. e.g. /yaya/resolve-identity',
          ),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _serviceAuthKind,
          decoration: const InputDecoration(
            labelText: 'Service auth (yaya → tenant)',
          ),
          items: const [
            DropdownMenuItem(value: 'NONE', child: Text('NONE')),
            DropdownMenuItem(value: 'BEARER', child: Text('BEARER (static)')),
            DropdownMenuItem(
                value: 'SIGNED_JWT',
                child: Text('SIGNED_JWT (per-request, recommended)')),
          ],
          onChanged: (v) =>
              setState(() => _serviceAuthKind = v ?? 'NONE'),
        ),
        if (_serviceAuthKind != 'NONE') ...[
          const SizedBox(height: 12),
          TextField(
            controller: _serviceSecretCtrl,
            obscureText: !_showSecret,
            decoration: InputDecoration(
              labelText: _editingId == null
                  ? 'Service secret'
                  : 'New service secret (leave blank to keep current)',
              helperText:
                  'Shared with the tenant webhook. Stored AES-GCM-encrypted; never displayed after save.',
              suffixIcon: IconButton(
                icon: Icon(_showSecret
                    ? Icons.visibility_off_outlined
                    : Icons.visibility_outlined),
                onPressed: () => setState(() => _showSecret = !_showSecret),
                tooltip: _showSecret ? 'Hide' : 'Show',
              ),
            ),
          ),
        ],
        const SizedBox(height: 12),
        TextField(
          controller: _timeoutCtrl,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'Timeout (ms)',
            helperText: 'Per-call timeout. 100–30000.',
          ),
        ),
      ],
    );
  }

  Widget _cacheSection() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Cache',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          SwitchListTile.adaptive(
            dense: true,
            contentPadding: EdgeInsets.zero,
            value: _cacheEnabled,
            onChanged: (v) => setState(() => _cacheEnabled = v),
            title: const Text('Cache resolver tokens'),
            subtitle: const Text(
                'When OFF, every session start fires the resolver. Default ON.'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _refreshWindowCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Refresh window (seconds)',
              helperText:
                  'Subtracted from the JWT exp when storing — proactive refresh window. 0–600.',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _maxTokenAgeCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Max token age (seconds)',
              helperText:
                  'Fallback when the JWT has no exp claim. 60–86400.',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _maxCacheSecondsCtrl,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'Max cache seconds (optional)',
              helperText:
                  'Ceiling on cache TTL. Use when your JWTs are long-lived but you want re-checks more often.',
            ),
          ),
        ],
      ),
    );
  }
}

class _ResolverTile extends StatelessWidget {
  const _ResolverTile({
    required this.row,
    required this.onEdit,
    required this.onDelete,
    required this.onTest,
  });
  final AuthResolverResponse row;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onTest;

  @override
  Widget build(BuildContext context) {
    final isHttp = row.kind == 'HTTP';
    return EditableRecordCard(
      onCloneEdit: onEdit,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(row.name, style: Theme.of(context).textTheme.titleSmall),
          Text(
              isHttp
                  ? 'HTTP · ${row.serviceAuthKind ?? "NONE"} · ${row.httpPath ?? ""}'
                  : 'BEAN · ${row.beanName ?? "?"}',
              style: const TextStyle(fontSize: 12)),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (row.description != null && row.description!.isNotEmpty)
            DetailRow(label: 'Description', value: row.description!),
          DetailRow(label: 'Kind', value: row.kind),
          if (isHttp) ...[
            DetailRow(label: 'Path', value: row.httpPath ?? '', mono: true),
            DetailRow(
                label: 'Service auth', value: row.serviceAuthKind ?? 'NONE'),
            if (row.serviceSecretMasked != null
                && row.serviceSecretMasked!.isNotEmpty)
              DetailRow(
                  label: 'Secret', value: row.serviceSecretMasked!, mono: true),
            DetailRow(label: 'Timeout (ms)', value: (row.timeoutMs ?? 3000).toString()),
          ] else
            DetailRow(label: 'Bean name', value: row.beanName ?? '', mono: true),
          DetailRow(
              label: 'Cache',
              value: row.cacheEnabled == false
                  ? 'disabled'
                  : 'enabled · refresh window ${row.refreshWindowSeconds ?? 60}s'),
          DetailRow(
              label: 'On refresh failure',
              value: row.onRefreshFailure ?? 'REJECT'),
          DetailRow(label: 'Status', value: row.status),
        ],
      ),
      extraActions: [
        TextButton.icon(
          onPressed: onTest,
          icon: const Icon(Icons.bolt_outlined, size: 18),
          label: const Text('Test'),
        ),
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

class _ProbeInputDialog extends StatefulWidget {
  const _ProbeInputDialog();
  @override
  State<_ProbeInputDialog> createState() => _ProbeInputDialogState();
}

class _ProbeInputDialogState extends State<_ProbeInputDialog> {
  final _callerCtrl = TextEditingController(text: '+15555550100');
  final _senderIdCtrl = TextEditingController();
  final _senderPhoneCtrl = TextEditingController();

  @override
  void dispose() {
    _callerCtrl.dispose();
    _senderIdCtrl.dispose();
    _senderPhoneCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Test resolver'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
                'Fire the resolver synchronously with a sentinel caller. '
                'The response shows transport, elapsed time, and JWT '
                'verification against your tenant trust roots.',
                style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 12),
            TextField(
              controller: _callerCtrl,
              decoration: const InputDecoration(
                labelText: 'Caller phone (E.164)',
                helperText: 'Default +15555550100 — a real customer phone is more useful.',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _senderIdCtrl,
              decoration: const InputDecoration(
                labelText: 'Sender id (optional)',
                helperText: 'Defaults to snd_test_probe.',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _senderPhoneCtrl,
              decoration: const InputDecoration(
                labelText: 'Sender phone (optional)',
                helperText: 'Defaults to +15555550199.',
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, null),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, AuthResolverProbeInput(
                callerPhone: _callerCtrl.text.trim(),
                senderId: _senderIdCtrl.text.trim(),
                senderPhone: _senderPhoneCtrl.text.trim(),
              )),
          child: const Text('Run test'),
        ),
      ],
    );
  }
}

class _ProbeResultCard extends StatelessWidget {
  const _ProbeResultCard({required this.result});
  final AuthResolverProbeResult result;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final headerColor = result.ok ? scheme.primaryContainer : scheme.errorContainer;
    final headerFg = result.ok ? scheme.onPrimaryContainer : scheme.onErrorContainer;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: headerColor,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Row(
                children: [
                  Icon(result.ok ? Icons.check_circle : Icons.error,
                      size: 16, color: headerFg),
                  const SizedBox(width: 6),
                  Text(result.ok ? 'Probe OK' : 'Probe failed',
                      style: TextStyle(
                          color: headerFg, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  Text(result.transport,
                      style: TextStyle(color: headerFg, fontSize: 12)),
                ],
              ),
            ),
            const SizedBox(height: 8),
            if (result.url != null && result.url!.isNotEmpty)
              DetailRow(label: 'URL', value: result.url!, mono: true),
            if (result.elapsedMs != null)
              DetailRow(label: 'Elapsed', value: '${result.elapsedMs}ms'),
            DetailRow(
                label: 'Token received', value: result.tokenReceived ? 'yes' : 'no'),
            if (result.errorStatus != null)
              DetailRow(label: 'Error status', value: result.errorStatus!),
            if (result.errorMessage != null)
              DetailRow(label: 'Error', value: result.errorMessage!),
            if (result.verification != null) ...[
              const Divider(),
              Text('JWT verification',
                  style: Theme.of(context).textTheme.titleSmall),
              const SizedBox(height: 6),
              DetailRow(
                  label: 'OK',
                  value: result.verification!.ok ? 'yes' : 'no'),
              if (result.verification!.reason != null)
                DetailRow(label: 'Reason', value: result.verification!.reason!),
              if (result.verification!.issuer != null)
                DetailRow(label: 'iss', value: result.verification!.issuer!),
              if (result.verification!.subject != null)
                DetailRow(label: 'sub', value: result.verification!.subject!),
              if (result.verification!.audience.isNotEmpty)
                DetailRow(label: 'aud', value: result.verification!.audience.join(', ')),
              if (result.verification!.exp != null)
                DetailRow(label: 'exp', value: result.verification!.exp!),
              if (result.verification!.kid != null)
                DetailRow(label: 'kid', value: result.verification!.kid!),
              if (result.verification!.profileId != null)
                DetailRow(label: '_profile_id', value: result.verification!.profileId!),
              if (result.verification!.scopes.isNotEmpty)
                DetailRow(label: 'scopes', value: result.verification!.scopes.join(' ')),
            ],
          ],
        ),
      ),
    );
  }
}
