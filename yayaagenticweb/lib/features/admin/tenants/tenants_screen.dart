import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../api/admin_api.dart';
import '../../../models/admin/clone.dart';
import '../../../models/admin/tenant.dart';
import '../shared/admin_shared.dart';

/// Reactive feed of every tenant the operator can see. Refreshed on every
/// admin write that touches a tenant row (registration, lifecycle change,
/// migrator apply).
final tenantsProvider = FutureProvider<List<TenantResponse>>((ref) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.listTenants();
});

/// Per-tenant health view — invalidated whenever the tenant detail is
/// edited or a migrator run finishes.
final tenantHealthProvider =
    FutureProvider.family<TenantHealthResponse, String>((ref, id) async {
  final api = await ref.watch(adminApiProvider.future);
  return api.tenantHealth(id);
});

class TenantsScreen extends ConsumerStatefulWidget {
  const TenantsScreen({super.key});
  @override
  ConsumerState<TenantsScreen> createState() => _TenantsScreenState();
}

class _TenantsScreenState extends ConsumerState<TenantsScreen> {
  final _idCtrl = TextEditingController();
  final _displayNameCtrl = TextEditingController();
  final _hostBaseCtrl = TextEditingController();
  final _hostAllowCtrl = TextEditingController();         // one per line
  final _inboundAllowCtrl = TextEditingController();      // one per line
  bool _requireHttps = true;
  bool _saving = false;
  String? _editingId;                                     // null on create

  // Wizard step counter so the form doesn't feel like a giant one-page
  // dump on first registration. Identity step → routing step.
  int _step = 0;

  void _resetForm() {
    setState(() {
      _editingId = null;
      _step = 0;
      _idCtrl.clear();
      _displayNameCtrl.clear();
      _hostBaseCtrl.clear();
      _hostAllowCtrl.clear();
      _inboundAllowCtrl.clear();
      _requireHttps = true;
    });
  }

  void _loadIntoForm(TenantResponse t) {
    setState(() {
      _editingId = t.id;
      _step = 0;
      _idCtrl.text = t.id;
      _displayNameCtrl.text = t.displayName;
      _hostBaseCtrl.text = t.hostBaseUrl ?? '';
      _hostAllowCtrl.text = t.hostBaseUrlAllowlist.join('\n');
      _inboundAllowCtrl.text = t.inboundOriginAllowlist.join('\n');
      _requireHttps = t.requireHttps;
    });
  }

  @override
  void dispose() {
    _idCtrl.dispose();
    _displayNameCtrl.dispose();
    _hostBaseCtrl.dispose();
    _hostAllowCtrl.dispose();
    _inboundAllowCtrl.dispose();
    super.dispose();
  }

  List<String> _splitLines(String raw) => raw
      .split('\n')
      .map((s) => s.trim())
      .where((s) => s.isNotEmpty)
      .toList();

  Future<void> _save() async {
    if (_idCtrl.text.trim().isEmpty) {
      showSnack(context, 'id is required', error: true);
      return;
    }
    if (_displayNameCtrl.text.trim().isEmpty) {
      showSnack(context, 'displayName is required', error: true);
      return;
    }
    setState(() => _saving = true);
    try {
      final api = await ref.read(adminApiProvider.future);
      final req = TenantRequest(
        id: _idCtrl.text.trim(),
        displayName: _displayNameCtrl.text.trim(),
        hostBaseUrl: _hostBaseCtrl.text.trim(),
        hostBaseUrlAllowlist: _splitLines(_hostAllowCtrl.text),
        inboundOriginAllowlist: _splitLines(_inboundAllowCtrl.text),
        requireHttps: _requireHttps,
      );
      if (_editingId == null) {
        await api.createTenant(req);
      } else {
        await api.patchTenant(_editingId!, req);
      }
      ref.invalidate(tenantsProvider);
      if (_editingId != null) {
        ref.invalidate(tenantHealthProvider(_editingId!));
      }
      if (!mounted) return;
      final wasEditing = _editingId != null;
      _resetForm();
      showSnack(context, wasEditing ? 'tenant updated' : 'tenant registered');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _suspend(String id) async {
    await _withLifecycle(() async {
      final api = await ref.read(adminApiProvider.future);
      await api.suspendTenant(id);
    }, success: 'suspended', tenantId: id);
  }

  Future<void> _resume(String id) async {
    await _withLifecycle(() async {
      final api = await ref.read(adminApiProvider.future);
      await api.resumeTenant(id);
    }, success: 'resumed', tenantId: id);
  }

  Future<void> _archive(String id) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Archive tenant?'),
        content: const Text(
            'Archived tenants reject all writes and tool dispatch. The row '
            'stays for audit but the id can never be reused.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Archive')),
        ],
      ),
    );
    if (confirmed != true) return;
    await _withLifecycle(() async {
      final api = await ref.read(adminApiProvider.future);
      await api.archiveTenant(id);
    }, success: 'archived', tenantId: id);
  }

  Future<void> _withLifecycle(Future<void> Function() op,
      {required String success, required String tenantId}) async {
    try {
      await op();
      ref.invalidate(tenantsProvider);
      ref.invalidate(tenantHealthProvider(tenantId));
      if (mounted) showSnack(context, success);
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  Future<void> _runMigrator(String tenant) async {
    try {
      final api = await ref.read(adminApiProvider.future);
      final plan = await api.migrateToolsToPath(tenant: tenant, dryRun: true);
      if (!mounted) return;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (_) => _MigratorDialog(plan: plan),
      );
      if (confirmed != true) return;
      await api.migrateToolsToPath(tenant: tenant, dryRun: false);
      ref.invalidate(tenantHealthProvider(tenant));
      if (mounted) showSnack(context, 'absolute → path: ${plan.candidates.length} tool(s) rewritten');
    } catch (e) {
      if (mounted) showSnack(context, formatError(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final tenants = ref.watch(tenantsProvider);
    return AdminTwoPane(
      title: 'Tenants',
      subtitle:
          'A tenant owns the trust bundle: host base URL, inbound origins, '
          'default auth + recorder. Outbound + inbound trust never drift.',
      list: tenants.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Text(formatError(e),
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        data: (list) => list.isEmpty
            ? const Center(child: Text('No tenants yet — register one to the right.'))
            : ListView.separated(
                itemBuilder: (_, i) => _TenantTile(
                  tenant: list[i],
                  onEdit: () => _loadIntoForm(list[i]),
                  onSuspend: () => _suspend(list[i].id),
                  onResume: () => _resume(list[i].id),
                  onArchive: () => _archive(list[i].id),
                  onMigrate: () => _runMigrator(list[i].id),
                ),
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemCount: list.length,
              ),
      ),
      form: SingleChildScrollView(child: _form(context)),
    );
  }

  Widget _form(BuildContext context) {
    final editing = _editingId != null;
    return FormCard(
      title: editing
          ? 'Editing ${_editingId!}'
          : (_step == 0 ? 'Register tenant — identity' : 'Register tenant — routing'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (!editing) _wizardStepper(),
          if (_step == 0 || editing) ...[
            TextField(
              controller: _idCtrl,
              enabled: !editing,
              decoration: const InputDecoration(
                labelText: 'id (slug)',
                helperText: 'lowercase alphanum + hyphen; immutable',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _displayNameCtrl,
              decoration: const InputDecoration(labelText: 'display name'),
            ),
          ],
          if (_step == 1 || editing) ...[
            if (!editing) const SizedBox(height: 16),
            TextField(
              controller: _hostBaseCtrl,
              decoration: const InputDecoration(
                labelText: 'hostBaseUrl',
                helperText:
                    'scheme://host[:port][/path]; HTTP tools resolve their paths against this',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _hostAllowCtrl,
              maxLines: 3,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              decoration: const InputDecoration(
                labelText: 'hostBaseUrl allowlist (one per line)',
                helperText: '* wildcard supported; opt-in override via X-Yaya-Host-Base-Url',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _inboundAllowCtrl,
              maxLines: 3,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              decoration: const InputDecoration(
                labelText: 'inboundOriginAllowlist (one per line)',
                helperText: 'browser Origin must match; empty = hostBaseUrl origin only',
              ),
            ),
            const SizedBox(height: 8),
            SwitchListTile(
              value: _requireHttps,
              onChanged: (v) => setState(() => _requireHttps = v),
              title: const Text('requireHttps'),
              subtitle: const Text(
                  'rejects http:// in hostBaseUrl + allowlists when on',
                  style: TextStyle(fontSize: 12)),
              contentPadding: EdgeInsets.zero,
            ),
          ],
        ],
      ),
      actions: [
        if (editing)
          TextButton(onPressed: _saving ? null : _resetForm, child: const Text('Cancel')),
        if (!editing && _step == 1)
          TextButton(
            onPressed: _saving ? null : () => setState(() => _step = 0),
            child: const Text('Back'),
          ),
        if (!editing && _step == 0)
          FilledButton(
            onPressed: _saving ? null : () => setState(() => _step = 1),
            child: const Text('Next'),
          )
        else
          FilledButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox.square(
                    dimension: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(editing ? 'Save changes' : 'Register tenant'),
          ),
      ],
    );
  }

  Widget _wizardStepper() {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        children: [
          _dot('1. Identity', selected: _step == 0, theme: theme),
          Expanded(
            child: Container(
              height: 1,
              margin: const EdgeInsets.symmetric(horizontal: 8),
              color: theme.colorScheme.outlineVariant,
            ),
          ),
          _dot('2. Routing', selected: _step == 1, theme: theme),
        ],
      ),
    );
  }

  Widget _dot(String label, {required bool selected, required ThemeData theme}) {
    return Text(
      label,
      style: theme.textTheme.labelLarge?.copyWith(
        color: selected ? theme.colorScheme.primary : theme.colorScheme.outline,
        fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
      ),
    );
  }
}

class _TenantTile extends ConsumerWidget {
  const _TenantTile({
    required this.tenant,
    required this.onEdit,
    required this.onSuspend,
    required this.onResume,
    required this.onArchive,
    required this.onMigrate,
  });

  final TenantResponse tenant;
  final VoidCallback onEdit;
  final VoidCallback onSuspend;
  final VoidCallback onResume;
  final VoidCallback onArchive;
  final VoidCallback onMigrate;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final archived = tenant.status == 'ARCHIVED';
    final suspended = tenant.status == 'SUSPENDED';
    final health = ref.watch(tenantHealthProvider(tenant.id));

    return EditableRecordCard(
      onCloneEdit: onEdit,                        // re-used label is fine — patches the tenant
      leading: _statusDot(tenant.status, theme),
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Text(tenant.id, style: theme.textTheme.titleSmall),
            const SizedBox(width: 8),
            Chip(
              label: Text(tenant.status, style: const TextStyle(fontSize: 11)),
              visualDensity: VisualDensity.compact,
              backgroundColor: archived
                  ? theme.colorScheme.errorContainer
                  : suspended
                      ? theme.colorScheme.tertiaryContainer
                      : theme.colorScheme.primaryContainer,
            ),
          ]),
          Text(tenant.displayName, style: theme.textTheme.bodyMedium),
          if (tenant.hostBaseUrl != null && tenant.hostBaseUrl!.isNotEmpty)
            Text(tenant.hostBaseUrl!,
                style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.outline)),
        ],
      ),
      trailing: health.maybeWhen(
        data: (h) => _HealthBadge(health: h),
        orElse: () => const SizedBox(
          width: 16, height: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          DetailRow(label: 'id', value: tenant.id),
          DetailRow(label: 'displayName', value: tenant.displayName),
          DetailRow(label: 'status', value: tenant.status),
          DetailRow(label: 'hostBaseUrl', value: tenant.hostBaseUrl ?? '(unset)'),
          DetailRow(
            label: 'hostBaseUrlAllowlist',
            value: tenant.hostBaseUrlAllowlist.isEmpty
                ? '(empty)'
                : tenant.hostBaseUrlAllowlist.join('\n'),
            mono: tenant.hostBaseUrlAllowlist.isNotEmpty,
          ),
          DetailRow(
            label: 'inboundOriginAllowlist',
            value: tenant.inboundOriginAllowlist.isEmpty
                ? '(hostBaseUrl origin only)'
                : tenant.inboundOriginAllowlist.join('\n'),
            mono: tenant.inboundOriginAllowlist.isNotEmpty,
          ),
          DetailRow(label: 'requireHttps', value: tenant.requireHttps.toString()),
          if (tenant.defaultAuthenticatorBindingId != null)
            DetailRow(
                label: 'defaultAuthenticatorBindingId',
                value: tenant.defaultAuthenticatorBindingId!),
          const SizedBox(height: 12),
          _TenantHealthCard(
              tenantId: tenant.id,
              onMigrate: onMigrate),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              if (!archived && !suspended)
                OutlinedButton.icon(
                  onPressed: onSuspend,
                  icon: const Icon(Icons.pause_circle_outline),
                  label: const Text('Suspend'),
                ),
              if (suspended)
                FilledButton.tonalIcon(
                  onPressed: onResume,
                  icon: const Icon(Icons.play_circle_outline),
                  label: const Text('Resume'),
                ),
              if (!archived)
                OutlinedButton.icon(
                  onPressed: onArchive,
                  icon: const Icon(Icons.archive_outlined),
                  label: const Text('Archive'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: theme.colorScheme.error,
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _statusDot(String status, ThemeData theme) {
    final color = switch (status) {
      'ACTIVE' => theme.colorScheme.primary,
      'SUSPENDED' => theme.colorScheme.tertiary,
      'ARCHIVED' => theme.colorScheme.error,
      _ => theme.colorScheme.outline,
    };
    return Container(
      width: 10, height: 10,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
    );
  }
}

class _HealthBadge extends StatelessWidget {
  const _HealthBadge({required this.health});
  final TenantHealthResponse health;

  @override
  Widget build(BuildContext context) {
    final ok = health.hostBaseUrlSet
        && health.warnings.isEmpty;
    final theme = Theme.of(context);
    return Tooltip(
      message: ok
          ? 'Healthy'
          : 'Misconfigured — see warnings below',
      child: Icon(
        ok ? Icons.check_circle : Icons.warning_amber_rounded,
        color: ok ? theme.colorScheme.primary : theme.colorScheme.error,
        size: 20,
      ),
    );
  }
}

/// Per-tenant health detail surfaced inside the expanded tile. Pulls the
/// /v1/admin/tenants/{id}/health endpoint and renders one row per check
/// plus a "Migrate absolute URLs" action when relevant.
class _TenantHealthCard extends ConsumerWidget {
  const _TenantHealthCard({required this.tenantId, required this.onMigrate});
  final String tenantId;
  final VoidCallback onMigrate;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final health = ref.watch(tenantHealthProvider(tenantId));

    return Card(
      color: theme.colorScheme.surfaceContainer,
      elevation: 0,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: health.when(
          loading: () => const SizedBox(
            height: 32, child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Text(formatError(e),
              style: TextStyle(color: theme.colorScheme.error)),
          data: (h) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Health', style: theme.textTheme.titleSmall),
              const SizedBox(height: 8),
              _check(context, 'hostBaseUrl set', h.hostBaseUrlSet),
              _check(context, 'auth binding resolves', h.authBindingResolves),
              _check(context, 'recording strategy resolves', h.recordingStrategyResolves),
              if (h.warnings.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text('Warnings', style: theme.textTheme.labelLarge),
                const SizedBox(height: 4),
                ...h.warnings.map((w) => Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(Icons.warning_amber_rounded,
                              size: 16, color: theme.colorScheme.error),
                          const SizedBox(width: 6),
                          Expanded(child: Text(w, style: const TextStyle(fontSize: 12))),
                        ],
                      ),
                    )),
              ],
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton.icon(
                    onPressed: onMigrate,
                    icon: const Icon(Icons.compare_arrows),
                    label: const Text('Migrate absolute URLs → path'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _check(BuildContext context, String label, bool ok) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(children: [
        Icon(
          ok ? Icons.check_circle : Icons.cancel,
          size: 16,
          color: ok ? theme.colorScheme.primary : theme.colorScheme.error,
        ),
        const SizedBox(width: 6),
        Text(label, style: const TextStyle(fontSize: 13)),
      ]),
    );
  }
}

class _MigratorDialog extends StatelessWidget {
  const _MigratorDialog({required this.plan});
  final MigrateToPathPlan plan;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return AlertDialog(
      title: const Text('Migrate absolute URLs → path'),
      content: SizedBox(
        width: 540,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '${plan.candidates.length} tool(s) will be rewritten and '
                'version-bumped. ${plan.unsafe.length} are NOT safe to '
                'rewrite — host doesn\'t match this tenant; resolve them '
                'manually.',
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 12),
              if (plan.candidates.isNotEmpty) ...[
                Text('Candidates', style: theme.textTheme.labelLarge),
                const SizedBox(height: 4),
                ...plan.candidates.map((c) => _row(theme, c.toolId, c.current, c.rewritten)),
                const SizedBox(height: 12),
              ],
              if (plan.unsafe.isNotEmpty) ...[
                Text('Unsafe (not touched)',
                    style: theme.textTheme.labelLarge?.copyWith(
                        color: theme.colorScheme.error)),
                const SizedBox(height: 4),
                ...plan.unsafe.map((u) => _row(theme, u.toolId, u.current, u.reason, error: true)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
        FilledButton(
          onPressed: plan.candidates.isEmpty
              ? null
              : () => Navigator.pop(context, true),
          child: const Text('Apply'),
        ),
      ],
    );
  }

  Widget _row(ThemeData theme, String id, String from, String to,
      {bool error = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(id, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          Padding(
            padding: const EdgeInsets.only(left: 12),
            child: Text('$from  →  $to',
                style: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 11,
                  color: error ? theme.colorScheme.error : theme.colorScheme.outline,
                )),
          ),
        ],
      ),
    );
  }
}
