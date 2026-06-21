import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../models/admin/workspace.dart';
import '../application/workspaces_controller.dart';
import 'create_workspace_dialog.dart';
import 'role_chip.dart';

/// Caller's workspaces — grid of cards + create CTA. UI-UX §4.8.
class WorkspaceListScreen extends ConsumerWidget {
  const WorkspaceListScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final workspaces = ref.watch(myWorkspacesProvider);

    return Scaffold(
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Text('Your workspaces',
                    style: theme.textTheme.headlineMedium),
                const Spacer(),
                FilledButton.icon(
                  icon: const Icon(Icons.add),
                  label: const Text('New workspace'),
                  onPressed: () => _createWorkspace(context, ref),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Expanded(
              child: workspaces.when(
                loading: () => const _LoadingGrid(),
                error: (e, _) => _ErrorState(
                  message: 'Couldn\'t load your workspaces. $e',
                  onRetry: () => ref.invalidate(myWorkspacesProvider),
                ),
                data: (list) => list.isEmpty
                    ? _EmptyState(
                        onCreate: () => _createWorkspace(context, ref),
                      )
                    : _Grid(items: list, onCreate: () => _createWorkspace(context, ref)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _createWorkspace(BuildContext context, WidgetRef ref) async {
    final newCode = await showDialog<String>(
      context: context,
      builder: (_) => const CreateWorkspaceDialog(),
    );
    if (newCode != null) {
      await ref.read(currentWorkspaceProvider.notifier).switchTo(newCode);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Workspace "$newCode" created.')),
        );
      }
    }
  }
}

class _Grid extends ConsumerWidget {
  const _Grid({required this.items, required this.onCreate});
  final List<WorkspaceListItem> items;
  final VoidCallback onCreate;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentCode = ref.watch(currentWorkspaceOrNull);
    return LayoutBuilder(
      builder: (_, constraints) {
        // Two-column grid on tablet+; single column on narrow.
        final cols = constraints.maxWidth > 720 ? 2 : 1;
        return GridView.builder(
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: cols,
            mainAxisExtent: 180,
            crossAxisSpacing: 12,
            mainAxisSpacing: 12,
          ),
          itemCount: items.length + 1,                 // +1 for the create card
          itemBuilder: (context, i) {
            if (i == items.length) return _CreateCard(onTap: onCreate);
            final m = items[i];
            return _WorkspaceCard(
              membership: m,
              current: m.workspace.code == currentCode,
              onOpen: () async {
                await ref.read(currentWorkspaceProvider.notifier)
                    .switchTo(m.workspace.code);
                if (context.mounted) context.go('/playground');
              },
            );
          },
        );
      },
    );
  }
}

class _WorkspaceCard extends StatelessWidget {
  const _WorkspaceCard({
    required this.membership,
    required this.current,
    required this.onOpen,
  });
  final WorkspaceListItem membership;
  final bool current;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final archived = membership.workspace.status == 'ARCHIVED';
    return Card(
      elevation: current ? 2 : 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(
          color: current
              ? theme.colorScheme.primary
              : theme.colorScheme.outlineVariant,
          width: current ? 1.5 : 1,
        ),
      ),
      child: InkWell(
        onTap: archived ? null : onOpen,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(membership.workspace.displayName,
                            style: theme.textTheme.titleMedium,
                            overflow: TextOverflow.ellipsis),
                        const SizedBox(height: 2),
                        Text(membership.workspace.code,
                            style: theme.textTheme.bodySmall?.copyWith(
                                color: theme.colorScheme.outline,
                                fontFamily: 'monospace')),
                      ],
                    ),
                  ),
                  RoleChip(membership.role),
                ],
              ),
              const Spacer(),
              if (archived)
                Row(
                  children: [
                    Icon(Icons.archive_outlined,
                        size: 14, color: theme.colorScheme.outline),
                    const SizedBox(width: 4),
                    Text('Archived',
                        style: theme.textTheme.bodySmall
                            ?.copyWith(color: theme.colorScheme.outline)),
                  ],
                )
              else
                Row(
                  children: [
                    if (current) ...[
                      Icon(Icons.check_circle,
                          size: 14, color: theme.colorScheme.primary),
                      const SizedBox(width: 4),
                      Text('Current',
                          style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.primary,
                              fontWeight: FontWeight.w600)),
                    ] else
                      TextButton.icon(
                        onPressed: onOpen,
                        icon: const Icon(Icons.arrow_forward, size: 16),
                        label: const Text('Open'),
                        style: TextButton.styleFrom(
                            padding: const EdgeInsets.symmetric(horizontal: 8)),
                      ),
                    const Spacer(),
                    Builder(builder: (context) {
                      return IconButton(
                        tooltip: 'Workspace settings',
                        icon: const Icon(Icons.settings_outlined, size: 18),
                        onPressed: () => GoRouter.of(context).go(
                          '/workspaces/${membership.workspace.code}/settings',
                        ),
                      );
                    }),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CreateCard extends StatelessWidget {
  const _CreateCard({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return DottedBorder(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.add_business_outlined,
                  size: 32, color: theme.colorScheme.primary),
              const SizedBox(height: 8),
              Text('Create workspace',
                  style: theme.textTheme.titleSmall?.copyWith(
                      color: theme.colorScheme.primary)),
              const SizedBox(height: 4),
              Text('Set up a new workspace for a team or project.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.outline)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Minimal dashed-border wrapper for the create card — keeps the visual
/// language matching standard create-affordance patterns without pulling
/// in a dotted_border package.
class DottedBorder extends StatelessWidget {
  const DottedBorder({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: theme.colorScheme.outlineVariant,
          width: 1.5,
        ),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: child,
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.onCreate});
  final VoidCallback onCreate;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.workspaces_outline,
                size: 56, color: theme.colorScheme.outline),
            const SizedBox(height: 12),
            Text("You don't belong to any workspaces yet",
                textAlign: TextAlign.center,
                style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text('Create your first workspace to get started.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: theme.colorScheme.outline)),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: onCreate,
              icon: const Icon(Icons.add),
              label: const Text('Create your first workspace'),
            ),
          ],
        ),
      ),
    );
  }
}

class _LoadingGrid extends StatelessWidget {
  const _LoadingGrid();
  @override
  Widget build(BuildContext context) {
    return GridView.count(
      crossAxisCount: 2,
      childAspectRatio: 2.5,
      crossAxisSpacing: 12,
      mainAxisSpacing: 12,
      children: List.generate(
        4,
        (_) => Card(
          child: SizedBox.expand(
            child: ColoredBox(
                color: Theme.of(context).colorScheme.surfaceContainerHighest),
          ),
        ),
      ),
    );
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.error_outline,
              size: 48, color: theme.colorScheme.error),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
            label: const Text('Retry'),
          ),
        ],
      ),
    );
  }
}
