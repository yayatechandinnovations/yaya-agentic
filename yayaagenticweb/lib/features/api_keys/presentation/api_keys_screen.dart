import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../models/admin/api_key.dart';
import '../../workspaces/application/workspaces_controller.dart';
import '../../workspaces/presentation/confirm_typed_name_dialog.dart';
import '../../workspaces/presentation/role_chip.dart';
import '../application/api_keys_controller.dart';
import 'api_key_audit_drawer.dart';
import 'create_api_key_dialog.dart';
import 'show_secret_panel.dart';

/// Settings → API keys (design §3.1). Lists the current workspace's keys;
/// create/revoke are gated to ADMIN+ (server stays authoritative — the UI
/// gate is hygiene). Keyed on the current workspace, so switching workspaces
/// re-fetches and never crosses keys between them.
class ApiKeysScreen extends ConsumerWidget {
  const ApiKeysScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final code = ref.watch(currentWorkspaceOrNull);
    final membership = ref.watch(currentMembershipProvider);

    if (code == null) {
      return _ScaffoldBody(
        child: _EmptyState(
          icon: Icons.workspaces_outline,
          title: 'No workspace selected',
          body: 'Create or pick a workspace first — API keys are scoped to one.',
          action: FilledButton(
            onPressed: () => context.go('/workspaces'),
            child: const Text('Go to workspaces'),
          ),
        ),
      );
    }

    final role = membership?.role ?? 'VIEWER';
    final canManage = roleAtLeast(role, 'ADMIN');
    final keys = ref.watch(apiKeysProvider(code));

    return _ScaffoldBody(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('API keys', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(width: 12),
              if (membership != null) RoleChip(membership.role, dense: true),
              const Spacer(),
              _CreateButton(
                workspaceCode: code,
                creatorRole: role,
                enabled: canManage,
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            'Long-lived bearer credentials for apps, scripts, and CI jobs to call '
            'the yaya-agentic API without a user password. Bound to this workspace '
            'and a role.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.outline),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: keys.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(child: Text('Could not load keys: $e')),
              data: (rows) {
                if (rows.isEmpty) {
                  return _EmptyState(
                    icon: Icons.vpn_key_outlined,
                    title: 'No API keys yet',
                    body:
                        'API keys let your apps, scripts, and CI jobs call the '
                        'yaya-agentic API without storing a user password. Each '
                        'key is bound to this workspace and a role.',
                    action: canManage
                        ? FilledButton.icon(
                            onPressed: () =>
                                _createFlow(context, ref, code, role),
                            icon: const Icon(Icons.add),
                            label: const Text('Create key'),
                          )
                        : null,
                  );
                }
                return _KeysTable(
                  workspaceCode: code,
                  rows: rows,
                  canManage: canManage,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

Future<void> _createFlow(
    BuildContext context, WidgetRef ref, String code, String role) async {
  final created = await showDialog<ApiKeyCreated>(
    context: context,
    builder: (_) => CreateApiKeyDialog(workspaceCode: code, creatorRole: role),
  );
  if (created != null && context.mounted) {
    await ShowSecretDialog.show(context, created);
  }
}

class _CreateButton extends ConsumerWidget {
  const _CreateButton({
    required this.workspaceCode,
    required this.creatorRole,
    required this.enabled,
  });
  final String workspaceCode;
  final String creatorRole;
  final bool enabled;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final btn = FilledButton.icon(
      onPressed: enabled
          ? () => _createFlow(context, ref, workspaceCode, creatorRole)
          : null,
      icon: const Icon(Icons.add),
      label: const Text('Create key'),
    );
    if (enabled) return btn;
    return Tooltip(
      message: 'Only OWNER and ADMIN can create keys.',
      child: btn,
    );
  }
}

class _KeysTable extends ConsumerWidget {
  const _KeysTable({
    required this.workspaceCode,
    required this.rows,
    required this.canManage,
  });
  final String workspaceCode;
  final List<ApiKey> rows;
  final bool canManage;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: SingleChildScrollView(
        child: DataTable(
          columns: const [
            DataColumn(label: Text('Name')),
            DataColumn(label: Text('Prefix')),
            DataColumn(label: Text('Role')),
            DataColumn(label: Text('Created by')),
            DataColumn(label: Text('Last used')),
            DataColumn(label: Text('Expires')),
            DataColumn(label: Text('Status')),
            DataColumn(label: Text('')),
          ],
          rows: rows
              .map((k) => DataRow(cells: [
                    DataCell(Text(k.name)),
                    DataCell(Text('${k.keyPrefix}…',
                        style: const TextStyle(fontFamily: 'monospace'))),
                    DataCell(RoleChip(k.role, dense: true)),
                    DataCell(Text(k.createdBy ?? '—')),
                    DataCell(Text(_fmt(k.lastUsedAt, whenNull: 'never'))),
                    DataCell(_expiresCell(context, k)),
                    DataCell(_statusChip(context, k.status)),
                    DataCell(_RowActions(
                      workspaceCode: workspaceCode,
                      apiKey: k,
                      canManage: canManage,
                    )),
                  ]))
              .toList(),
        ),
      ),
    );
  }

  Widget _expiresCell(BuildContext context, ApiKey k) {
    if (k.expiresAt == null) return const Text('never');
    if (k.status == 'expired') {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.errorContainer,
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text('expired',
            style: TextStyle(
                fontSize: 11,
                color: Theme.of(context).colorScheme.onErrorContainer)),
      );
    }
    return Text(_fmt(k.expiresAt, whenNull: 'never'));
  }

  Widget _statusChip(BuildContext context, String status) {
    final c = Theme.of(context).colorScheme;
    final (bg, fg) = switch (status) {
      'active' => (c.primaryContainer, c.onPrimaryContainer),
      'revoked' => (c.surfaceContainerHigh, c.onSurfaceVariant),
      'expired' => (c.errorContainer, c.onErrorContainer),
      _ => (c.surfaceContainerHigh, c.onSurfaceVariant),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(99)),
      child: Text(status,
          style: TextStyle(color: fg, fontSize: 11, fontWeight: FontWeight.w600)),
    );
  }
}

class _RowActions extends ConsumerWidget {
  const _RowActions({
    required this.workspaceCode,
    required this.apiKey,
    required this.canManage,
  });
  final String workspaceCode;
  final ApiKey apiKey;
  final bool canManage;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final revocable = canManage && apiKey.status == 'active';
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          tooltip: 'Audit',
          icon: const Icon(Icons.history, size: 20),
          onPressed: canManage
              ? () => ApiKeyAuditDialog.show(context, workspaceCode, apiKey)
              : null,
        ),
        IconButton(
          tooltip: revocable
              ? 'Revoke'
              : (canManage ? 'Already inactive' : 'ADMIN+ only'),
          icon: const Icon(Icons.delete_outline, size: 20),
          onPressed: revocable ? () => _revoke(context, ref) : null,
        ),
      ],
    );
  }

  Future<void> _revoke(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => ConfirmTypedNameDialog(
        title: 'Revoke API key',
        body: Text(
          'Revoking “${apiKey.name}” (${apiKey.keyPrefix}…) is immediate and '
          'cannot be undone. Any caller using this key will start getting 401s.',
        ),
        expectedName: apiKey.name,
        confirmLabel: 'Revoke',
      ),
    );
    if (ok != true || !context.mounted) return;
    try {
      await ref.read(apiKeysControllerProvider).revoke(
            workspaceCode: workspaceCode,
            keyId: apiKey.id,
          );
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Key revoked.')),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Revoke failed: $e')),
        );
      }
    }
  }
}

class _ScaffoldBody extends StatelessWidget {
  const _ScaffoldBody({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1100),
        child: child,
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.icon,
    required this.title,
    required this.body,
    this.action,
  });
  final IconData icon;
  final String title;
  final String body;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: theme.colorScheme.outline),
            const SizedBox(height: 12),
            Text(title, style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(body,
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: theme.colorScheme.outline)),
            if (action != null) ...[const SizedBox(height: 16), action!],
          ],
        ),
      ),
    );
  }
}

/// ISO-8601 → 'YYYY-MM-DD HH:MM' (local), or [whenNull] when absent/unparseable.
String _fmt(String? iso, {required String whenNull}) {
  if (iso == null || iso.isEmpty) return whenNull;
  final dt = DateTime.tryParse(iso);
  if (dt == null) return whenNull;
  final l = dt.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${l.year}-${two(l.month)}-${two(l.day)} ${two(l.hour)}:${two(l.minute)}';
}
