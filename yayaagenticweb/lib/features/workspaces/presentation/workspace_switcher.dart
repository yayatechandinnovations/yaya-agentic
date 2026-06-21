import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../application/workspaces_controller.dart';
import 'create_workspace_dialog.dart';
import 'role_chip.dart';

/// Compact chip + dropdown sitting in the admin shell. Switching changes
/// [currentWorkspaceProvider]; the bridge then pushes the new code into
/// [selectedTenantProvider] so every legacy admin screen re-fetches in
/// step. See UI-UX §5.1.
class WorkspaceSwitcher extends ConsumerWidget {
  const WorkspaceSwitcher({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final workspaces = ref.watch(myWorkspacesProvider);
    final currentCode = ref.watch(currentWorkspaceOrNull);

    return workspaces.when(
      loading: () => const Padding(
        padding: EdgeInsets.symmetric(horizontal: 12),
        child: SizedBox.square(
          dimension: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      ),
      error: (_, __) => const SizedBox.shrink(),
      data: (list) {
        final active =
            list.where((m) => m.workspace.status != 'ARCHIVED').toList();
        if (active.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: TextButton.icon(
              icon: const Icon(Icons.add_business_outlined, size: 18),
              label: const Text('Create workspace'),
              onPressed: () => _create(context, ref),
            ),
          );
        }
        final current = active.firstWhere(
          (m) => m.workspace.code == currentCode,
          orElse: () => active.first,
        );

        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: PopupMenuButton<_SwitcherAction>(
            tooltip: 'Switch workspace',
            position: PopupMenuPosition.under,
            onSelected: (action) async {
              switch (action) {
                case _SwitchTo(:final code):
                  await ref
                      .read(currentWorkspaceProvider.notifier)
                      .switchTo(code);
                case _Create():
                  await _create(context, ref);
                case _Manage():
                  if (context.mounted) context.go('/workspaces');
              }
            },
            itemBuilder: (_) => [
              for (final m in active)
                PopupMenuItem<_SwitcherAction>(
                  value: _SwitchTo(m.workspace.code),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        m.workspace.code == currentCode
                            ? Icons.check
                            : Icons.workspaces_outline,
                        size: 18,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(width: 8),
                      ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 180),
                        child: Text(m.workspace.displayName,
                            overflow: TextOverflow.ellipsis),
                      ),
                      const SizedBox(width: 8),
                      RoleChip(m.role, dense: true),
                    ],
                  ),
                ),
              const PopupMenuDivider(),
              const PopupMenuItem<_SwitcherAction>(
                value: _Manage(),
                child: ListTile(
                  leading: Icon(Icons.list_alt, size: 18),
                  title: Text('All workspaces'),
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                ),
              ),
              const PopupMenuItem<_SwitcherAction>(
                value: _Create(),
                child: ListTile(
                  leading: Icon(Icons.add, size: 18),
                  title: Text('New workspace'),
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                ),
              ),
            ],
            child: Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(20),
                border:
                    Border.all(color: theme.colorScheme.outlineVariant),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.workspaces_outline,
                      size: 16, color: theme.colorScheme.onSurfaceVariant),
                  const SizedBox(width: 6),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 160),
                    child: Text(
                      current.workspace.displayName,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w500),
                    ),
                  ),
                  const SizedBox(width: 6),
                  Icon(Icons.arrow_drop_down,
                      size: 18, color: theme.colorScheme.onSurfaceVariant),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Future<void> _create(BuildContext context, WidgetRef ref) async {
    final newCode = await showDialog<String>(
      context: context,
      builder: (_) => const CreateWorkspaceDialog(),
    );
    if (newCode != null) {
      await ref
          .read(currentWorkspaceProvider.notifier)
          .switchTo(newCode);
    }
  }
}

sealed class _SwitcherAction {
  const _SwitcherAction();
}

class _SwitchTo extends _SwitcherAction {
  const _SwitchTo(this.code);
  final String code;
}

class _Create extends _SwitcherAction {
  const _Create();
}

class _Manage extends _SwitcherAction {
  const _Manage();
}
