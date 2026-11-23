import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../app/live/live_resource.dart';
import '../../../app/live/resource_kind.dart';
import '../../../models/admin/api_key.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/tokens.dart';
import '../../workspaces/application/workspaces_controller.dart';
import '../../workspaces/presentation/role_chip.dart';
import '../application/api_keys_controller.dart';
import 'api_key_sheet.dart';
import 'create_api_key_dialog.dart';
import 'show_secret_panel.dart';

/// Settings → API keys for the current workspace. Create, rename and revoke
/// are for admins and owners; the server stays authoritative. Keyed on the
/// current workspace, so switching never shows another workspace's keys.
class ApiKeysScreen extends ConsumerStatefulWidget {
  const ApiKeysScreen({super.key});

  @override
  ConsumerState<ApiKeysScreen> createState() => _ApiKeysScreenState();
}

class _ApiKeysScreenState extends ConsumerState<ApiKeysScreen> {
  String? _handledLink;

  Future<void> _create(String code, String role) async {
    final created = await showYSheet<ApiKeyCreated>(
      context,
      width: 620,
      label: 'New API key',
      builder: (_) => CreateApiKeySheet(workspaceCode: code, creatorRole: role),
    );
    if (created == null || !mounted) return;
    await ShowSecretDialog.show(context, created);
    if (mounted) showToast(context, 'Created ${created.name}');
  }

  Future<void> _open(String code, ApiKey k, bool canManage) => showYSheet<void>(
        context,
        width: 620,
        label: 'API key ${k.name}',
        builder: (_) => ApiKeySheet(workspaceCode: code, apiKey: k, canManage: canManage),
      );

  void _handleDeepLink(String code, String role, bool canManage, List<ApiKey> keys) {
    final params = GoRouterState.of(context).uri.queryParameters;
    final link = params['id'] ?? (params['new'] != null ? '' : null);
    if (link == null || link == _handledLink) return;
    _handledLink = link;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      context.go('/settings/api-keys');
      if (link.isEmpty) {
        if (canManage) await _create(code, role);
      } else {
        final k = keys.where((k) => k.id == link || k.name == link).firstOrNull;
        if (k == null) {
          showToast(context, 'No API key “$link” in this workspace', error: true);
        } else {
          await _open(code, k, canManage);
        }
      }
      _handledLink = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final code = ref.watch(currentWorkspaceOrNull);
    final membership = ref.watch(currentMembershipProvider);
    if (code == null) {
      return YEmptyState(
        icon: Icons.workspaces_outline,
        title: 'No workspace selected',
        message: 'API keys belong to one workspace. Create or pick a workspace first.',
        action: FilledButton(onPressed: () => context.go('/workspaces'), child: const Text('Go to workspaces')),
      );
    }
    final role = membership?.role ?? 'VIEWER';
    final canManage = roleAtLeast(role, 'ADMIN');
    final keys = ref.watch(apiKeysProvider(code));
    final fresh = ref.watch(freshIdsProvider(AdminResourceKind.apiKey));
    keys.whenData((k) => _handleDeepLink(code, role, canManage, k));

    final createBtn = Tooltip(
      message: canManage ? '' : 'Only admins and owners can create keys.',
      child: FilledButton.icon(
        onPressed: canManage ? () => _create(code, role) : null,
        icon: const Icon(Icons.add, size: 18),
        label: const Text('New API key'),
      ),
    );

    return YPage(
      title: 'API keys',
      subtitle: 'Long-lived credentials for apps, scripts and CI jobs to call the yaya-agentic API without a user password. Each key is bound to ${membership?.workspace.displayName ?? code} and a role.',
      actions: [if (membership != null) RoleChip(membership.role), createBtn],
      child: LiveAsyncView<List<ApiKey>>(
        value: keys,
        what: 'API keys',
        onRetry: () => ref.invalidate(apiKeysProvider(code)),
        data: (context, rows) => YTable<ApiKey>(
          rows: rows,
          noun: 'keys',
          idOf: (k) => k.id,
          highlightIds: fresh,
          searchHint: 'Search by name, prefix or creator',
          searchTextOf: (k) => '${k.name} ${k.keyPrefix} ${k.role} ${k.createdBy ?? ''} ${k.status}',
          onRowTap: (k) => _open(code, k, canManage),
          filters: [
            YFilter('Active', (k) => k.status == 'active'),
            YFilter('Never used', (k) => k.status == 'active' && k.lastUsedAt == null),
            YFilter('Revoked or expired', (k) => k.status != 'active'),
          ],
          empty: YEmptyState(
            icon: Icons.vpn_key_outlined,
            title: 'No API keys yet',
            message: 'An API key lets a service call the yaya-agentic API as this workspace, with a role you choose — no user password stored anywhere.',
            action: canManage ? createBtn : null,
          ),
          columns: [
            YColumn(
              label: 'Key',
              flex: 3,
              sortKey: (k) => k.name.toLowerCase(),
              cell: (context, k) => YCellStack(primary: k.name, secondary: '${k.keyPrefix}…'),
            ),
            YColumn(
              label: 'Role',
              width: 150,
              sortKey: (k) => k.role,
              cell: (context, k) => Wrap(spacing: 4, runSpacing: 4, children: [
                RoleChip(k.role),
                if (k.platformScope) const YBadge('Platform', tone: YTone.warning, icon: Icons.public),
              ]),
            ),
            YColumn(label: 'Status', width: 120, sortKey: (k) => k.status, cell: (context, k) => keyStatusBadge(k.status)),
            YColumn(
              label: 'Last used',
              width: 130,
              sortKey: (k) => k.lastUsedAt ?? '',
              cell: (context, k) => k.lastUsedAt == null
                  ? Text('Never', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
                  : Tooltip(message: k.lastUsedIp ?? '', child: Text(formatRelativeIso(k.lastUsedAt))),
            ),
            YColumn(
              label: 'Expires',
              width: 130,
              sortKey: (k) => k.expiresAt ?? '9999',
              cell: (context, k) => k.expiresAt == null
                  ? const YBadge('Never', icon: Icons.all_inclusive)
                  : Tooltip(message: k.expiresAt!, child: Text(_expiry(k.expiresAt!))),
            ),
            YColumn(
              label: 'Created by',
              flex: 2,
              sortKey: (k) => k.createdBy ?? '',
              cell: (context, k) => Text(k.createdBy ?? '—', overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
            ),
          ],
        ),
      ),
    );
  }

  static String _expiry(String iso) {
    final t = DateTime.tryParse(iso);
    if (t == null) return iso;
    final d = t.difference(DateTime.now());
    if (d.isNegative) return formatRelativeIso(iso);
    if (d.inDays >= 1) return 'in ${d.inDays} ${d.inDays == 1 ? 'day' : 'days'}';
    return 'in ${d.inHours.clamp(1, 23)} h';
  }
}
