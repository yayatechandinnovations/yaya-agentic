import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../models/admin/workspace.dart';
import '../../../ui/data/live_async.dart';
import '../../../ui/data/relative_time.dart';
import '../../../ui/data/y_badge.dart';
import '../../../ui/data/y_page.dart';
import '../../../ui/data/y_sheet.dart';
import '../../../ui/data/y_states.dart';
import '../../../ui/data/y_table.dart';
import '../../../ui/feedback/toast.dart';
import '../../../ui/tokens.dart';
import '../application/workspaces_controller.dart';
import 'create_workspace_dialog.dart';
import 'role_chip.dart';

/// The caller's workspaces: switch into one, open its settings, or create one.
class WorkspaceListScreen extends ConsumerStatefulWidget {
  const WorkspaceListScreen({super.key});

  @override
  ConsumerState<WorkspaceListScreen> createState() => _WorkspaceListScreenState();
}

class _WorkspaceListScreenState extends ConsumerState<WorkspaceListScreen> {
  bool _handledNew = false;

  Future<void> _create() async {
    final code = await showYSheet<String>(
      context,
      label: 'New workspace',
      builder: (_) => const CreateWorkspaceDialog(asSheet: true),
    );
    if (code == null || !mounted) return;
    await ref.read(currentWorkspaceProvider.notifier).switchTo(code);
    if (mounted) {
      showToast(context, 'Created workspace $code — you’re now working in it',
          actionLabel: 'Settings', onAction: () => context.go('/workspaces/$code/settings'));
    }
  }

  Future<void> _switch(WorkspaceListItem m) async {
    await ref.read(currentWorkspaceProvider.notifier).switchTo(m.workspace.code);
    if (mounted) showToast(context, 'Now working in ${m.workspace.displayName}');
  }

  @override
  Widget build(BuildContext context) {
    final params = GoRouterState.of(context).uri.queryParameters;
    if (params['new'] != null && !_handledNew) {
      _handledNew = true;
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted) return;
        context.go('/workspaces');
        await _create();
        _handledNew = false;
      });
    }
    final workspaces = ref.watch(myWorkspacesProvider);
    final current = ref.watch(currentWorkspaceOrNull);

    return YPage(
      title: 'Workspaces',
      subtitle: 'Each workspace is a team’s own set of profiles, tools, knowledge, members and API keys. Switch into one to work on it.',
      actions: [
        FilledButton.icon(onPressed: _create, icon: const Icon(Icons.add, size: 18), label: const Text('New workspace')),
      ],
      child: LiveAsyncView<List<WorkspaceListItem>>(
        value: workspaces,
        what: 'your workspaces',
        onRetry: () => ref.invalidate(myWorkspacesProvider),
        data: (context, list) => YTable<WorkspaceListItem>(
          rows: list,
          noun: 'workspaces',
          idOf: (m) => m.workspace.code,
          selectedId: current,
          searchHint: 'Search by name or code',
          searchTextOf: (m) => '${m.workspace.displayName} ${m.workspace.code} ${m.role}',
          onRowTap: (m) => context.go('/workspaces/${m.workspace.code}/settings'),
          filters: [
            YFilter('Active', (m) => m.workspace.status != 'ARCHIVED'),
            YFilter('I manage', (m) => roleAtLeastTier(m.role, 'ADMIN')),
            YFilter('Archived', (m) => m.workspace.status == 'ARCHIVED'),
          ],
          empty: YEmptyState(
            icon: Icons.workspaces_outline,
            title: 'You’re not in any workspace yet',
            message: 'A workspace holds a team’s agents and the people who build them. Create one, or ask a teammate to invite you.',
            action: FilledButton.icon(onPressed: _create, icon: const Icon(Icons.add, size: 18), label: const Text('Create your first workspace')),
          ),
          columns: [
            YColumn(
              label: 'Workspace',
              flex: 3,
              sortKey: (m) => m.workspace.displayName.toLowerCase(),
              cell: (context, m) => YCellStack(primary: m.workspace.displayName, secondary: m.workspace.code),
            ),
            YColumn(label: 'Your role', width: 150, sortKey: (m) => m.role, cell: (context, m) => RoleChip(m.role)),
            YColumn(
              label: 'Status',
              width: 130,
              sortKey: (m) => m.workspace.status ?? '',
              cell: (context, m) => m.workspace.status == 'ARCHIVED'
                  ? const YBadge('Archived', icon: Icons.archive_outlined)
                  : m.workspace.code == current
                      ? const YBadge('Current', tone: YTone.brand, icon: Icons.check_circle_outline)
                      : const YBadge('Active', tone: YTone.success),
            ),
            YColumn(
              label: 'Host base URL',
              flex: 3,
              cell: (context, m) => (m.workspace.hostBaseUrl ?? '').isEmpty
                  ? Text('Not set', style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
                  : Text(m.workspace.hostBaseUrl!, overflow: TextOverflow.ellipsis, style: monoStyle(context, size: 12.5)),
            ),
            YColumn(
              label: 'Created',
              width: 130,
              sortKey: (m) => m.workspace.createdAt ?? '',
              cell: (context, m) => Text(formatRelativeIso(m.workspace.createdAt)),
            ),
            YColumn(
              label: '',
              width: 120,
              alignEnd: true,
              cell: (context, m) => m.workspace.status == 'ARCHIVED' || m.workspace.code == current
                  ? const SizedBox.shrink()
                  : OutlinedButton(onPressed: () => _switch(m), child: const Text('Switch to')),
            ),
          ],
        ),
      ),
    );
  }
}
