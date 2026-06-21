import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../models/admin/invitation.dart';
import '../../../models/admin/member.dart';
import '../../../models/admin/workspace.dart';
import '../../auth/application/auth_controller.dart';
import '../../auth/models/auth_state.dart';
import '../application/members_controller.dart';
import '../application/workspaces_controller.dart';
import '../data/members_api.dart';
import '../data/workspaces_api.dart';
import 'confirm_typed_name_dialog.dart';
import 'invite_member_dialog.dart';
import 'role_chip.dart';

/// Per-workspace settings hub. Tabs match the UI-UX §4.9 spec:
/// General · Members · Invitations · Danger zone.
///
/// Role-aware gating happens at the button level — actions the caller
/// can't perform render disabled with a tooltip, never hidden. Server
/// remains authoritative; the UI gate is hygiene, not security
/// (UI-UX §1.1 design principle 5).
class WorkspaceSettingsScreen extends ConsumerStatefulWidget {
  const WorkspaceSettingsScreen({super.key, required this.code});
  final String code;

  @override
  ConsumerState<WorkspaceSettingsScreen> createState() =>
      _WorkspaceSettingsScreenState();
}

class _WorkspaceSettingsScreenState
    extends ConsumerState<WorkspaceSettingsScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tab = TabController(length: 4, vsync: this);

  @override
  void dispose() {
    _tab.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final workspaces = ref.watch(myWorkspacesProvider);

    return workspaces.when(
      loading: () => const Scaffold(
          body: Center(child: CircularProgressIndicator())),
      error: (e, _) => Scaffold(
        body: Center(child: Text('Could not load workspace: $e')),
      ),
      data: (list) {
        final mine = list.where((m) => m.workspace.code == widget.code).toList();
        if (mine.isEmpty) {
          // 404-as-no-access — render the same view regardless of which
          // branch we're on (UI-UX §1.1 design principle 3).
          return _NoAccessView(onBack: () => context.go('/workspaces'));
        }
        final membership = mine.first;
        return Scaffold(
          appBar: AppBar(
            title: Row(
              children: [
                Expanded(child: Text(membership.workspace.displayName)),
                const SizedBox(width: 8),
                RoleChip(membership.role),
              ],
            ),
            bottom: TabBar(
              controller: _tab,
              tabs: const [
                Tab(text: 'General'),
                Tab(text: 'Members'),
                Tab(text: 'Invitations'),
                Tab(text: 'Danger zone'),
              ],
            ),
          ),
          body: TabBarView(
            controller: _tab,
            children: [
              _GeneralTab(workspace: membership.workspace, role: membership.role),
              _MembersTab(workspaceCode: widget.code, actorRole: membership.role),
              _InvitationsTab(workspaceCode: widget.code, actorRole: membership.role),
              _DangerZoneTab(workspace: membership.workspace, role: membership.role),
            ],
          ),
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// General
// ---------------------------------------------------------------------------

class _GeneralTab extends ConsumerStatefulWidget {
  const _GeneralTab({required this.workspace, required this.role});
  final Workspace workspace;
  final String role;

  @override
  ConsumerState<_GeneralTab> createState() => _GeneralTabState();
}

class _GeneralTabState extends ConsumerState<_GeneralTab> {
  late final TextEditingController _name =
      TextEditingController(text: widget.workspace.displayName);
  bool _saving = false;
  String? _error;
  String? _ok;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  bool get _canEdit => _atLeast(widget.role, 'ADMIN');

  Future<void> _save() async {
    if (_saving) return;
    setState(() {
      _saving = true;
      _error = null;
      _ok = null;
    });
    try {
      final api = await ref.read(workspacesApiProvider.future);
      await api.patch(widget.workspace.code, _name.text.trim());
      ref.invalidate(myWorkspacesProvider);
      if (!mounted) return;
      setState(() {
        _saving = false;
        _ok = 'Saved.';
      });
    } on WorkspaceApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = e.message;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Workspace details', style: theme.textTheme.titleLarge),
            const SizedBox(height: 16),
            TextField(
              controller: _name,
              enabled: _canEdit,
              decoration: const InputDecoration(
                labelText: 'Display name',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            _ReadOnlyField(
              label: 'URL slug',
              value: widget.workspace.code,
              hint: 'Immutable — used in URLs and logs.',
            ),
            const SizedBox(height: 12),
            _ReadOnlyField(
              label: 'Workspace id',
              value: widget.workspace.id,
              hint: 'Stable internal identifier.',
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(_error!,
                  style: TextStyle(color: theme.colorScheme.error)),
            ],
            if (_ok != null) ...[
              const SizedBox(height: 12),
              Text(_ok!,
                  style: TextStyle(color: theme.colorScheme.primary)),
            ],
            const SizedBox(height: 16),
            Align(
              alignment: Alignment.centerRight,
              child: Tooltip(
                message: _canEdit ? '' : 'Requires ADMIN role.',
                child: FilledButton(
                  onPressed: (_canEdit && !_saving) ? _save : null,
                  child: _saving
                      ? const SizedBox(
                          height: 16,
                          width: 16,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Save changes'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ReadOnlyField extends StatelessWidget {
  const _ReadOnlyField(
      {required this.label, required this.value, required this.hint});
  final String label;
  final String value;
  final String hint;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: theme.textTheme.labelMedium),
        const SizedBox(height: 4),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: theme.colorScheme.surfaceContainerHigh,
            borderRadius: BorderRadius.circular(4),
          ),
          child: Text(value, style: const TextStyle(fontFamily: 'monospace')),
        ),
        const SizedBox(height: 4),
        Text(hint,
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.outline)),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Members
// ---------------------------------------------------------------------------

class _MembersTab extends ConsumerWidget {
  const _MembersTab({required this.workspaceCode, required this.actorRole});
  final String workspaceCode;
  final String actorRole;

  bool get _canManage => _atLeast(actorRole, 'ADMIN');

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final members = ref.watch(membersProvider(workspaceCode));
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text('Members', style: theme.textTheme.titleLarge),
              const Spacer(),
              Tooltip(
                message: _canManage ? '' : 'Requires ADMIN role.',
                child: FilledButton.icon(
                  icon: const Icon(Icons.person_add_alt_1),
                  label: const Text('Invite member'),
                  onPressed: _canManage
                      ? () => _openInviteDialog(context, ref)
                      : null,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: members.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) =>
                  Center(child: Text('Could not load members. $e')),
              data: (list) => list.isEmpty
                  ? const Center(child: Text('No members yet.'))
                  : _MembersTable(
                      workspaceCode: workspaceCode,
                      actorRole: actorRole,
                      members: list,
                    ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _openInviteDialog(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => InviteMemberDialog(
        workspaceCode: workspaceCode,
        actorRole: actorRole,
      ),
    );
    if (ok == true && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Invitation sent.')),
      );
    }
  }
}

class _MembersTable extends ConsumerWidget {
  const _MembersTable({
    required this.workspaceCode,
    required this.actorRole,
    required this.members,
  });
  final String workspaceCode;
  final String actorRole;
  final List<Member> members;

  static const _roles = ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'];

  bool get _isOwner => actorRole == 'OWNER' || actorRole == 'PLATFORM_ADMIN';
  bool get _canManage => _atLeast(actorRole, 'ADMIN');

  bool _canTouch(Member m) {
    if (!_canManage) return false;
    // ADMINs can't touch OWNER rows; OWNERs / PLATFORM_ADMIN can touch anything.
    if (m.role == 'OWNER' && !_isOwner) return false;
    return true;
  }

  List<String> _allowedTargetRoles(Member m) {
    // ADMINs may NOT promote to OWNER. Owners can.
    if (_isOwner) return _roles;
    return _roles.where((r) => r != 'OWNER').toList();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return Card(
      child: ListView.separated(
        itemCount: members.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, i) {
          final m = members[i];
          final touch = _canTouch(m);
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Row(
              children: [
                CircleAvatar(
                  backgroundColor: theme.colorScheme.surfaceContainerHighest,
                  child: Text(
                    (m.displayName?.isNotEmpty == true
                            ? m.displayName!
                            : (m.email ?? '?'))
                        .characters
                        .first
                        .toUpperCase(),
                    style: TextStyle(color: theme.colorScheme.onSurface),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(m.displayName ?? '(no name)',
                          style: theme.textTheme.bodyLarge,
                          overflow: TextOverflow.ellipsis),
                      Text(m.email ?? '',
                          style: theme.textTheme.bodySmall?.copyWith(
                              color: theme.colorScheme.outline),
                          overflow: TextOverflow.ellipsis),
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                if (touch)
                  _RoleDropdown(
                    current: m.role,
                    allowed: _allowedTargetRoles(m),
                    onChanged: (newRole) async {
                      try {
                        await ref.read(membersControllerProvider).changeRole(
                              workspaceCode: workspaceCode,
                              userId: m.userId,
                              newRole: newRole,
                            );
                      } on MembersApiException catch (e) {
                        if (!context.mounted) return;
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(e.message)),
                        );
                      }
                    },
                  )
                else
                  RoleChip(m.role),
                const SizedBox(width: 12),
                Tooltip(
                  message: touch ? 'Remove member' : '',
                  child: IconButton(
                    icon: const Icon(Icons.person_remove_alt_1_outlined),
                    onPressed: touch
                        ? () => _confirmRemove(context, ref, m)
                        : null,
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Future<void> _confirmRemove(
      BuildContext context, WidgetRef ref, Member m) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Remove member?'),
        content: Text(
            'This removes ${m.displayName ?? m.email ?? m.userId} from the workspace. '
            'Their account is not deleted; they simply lose access here.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(context).colorScheme.error),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(membersControllerProvider).removeMember(
            workspaceCode: workspaceCode,
            userId: m.userId,
          );
    } on MembersApiException catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message)),
      );
    }
  }
}

class _RoleDropdown extends StatelessWidget {
  const _RoleDropdown({
    required this.current,
    required this.allowed,
    required this.onChanged,
  });
  final String current;
  final List<String> allowed;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return DropdownButtonHideUnderline(
      child: DropdownButton<String>(
        value: current,
        borderRadius: BorderRadius.circular(8),
        items: [
          for (final r in allowed)
            DropdownMenuItem<String>(
              value: r,
              child: RoleChip(r, dense: true),
            ),
        ],
        onChanged: (v) {
          if (v != null && v != current) onChanged(v);
        },
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Invitations
// ---------------------------------------------------------------------------

class _InvitationsTab extends ConsumerWidget {
  const _InvitationsTab(
      {required this.workspaceCode, required this.actorRole});
  final String workspaceCode;
  final String actorRole;

  bool get _canManage => _atLeast(actorRole, 'ADMIN');

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final pending = ref.watch(invitationsProvider(workspaceCode));
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text('Pending invitations', style: theme.textTheme.titleLarge),
              const Spacer(),
              IconButton.outlined(
                icon: const Icon(Icons.refresh),
                onPressed: () => ref.invalidate(invitationsProvider(workspaceCode)),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: pending.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) =>
                  Center(child: Text('Could not load invitations. $e')),
              data: (list) {
                if (list.isEmpty) {
                  return Center(
                    child: Text('No pending invitations.',
                        style: theme.textTheme.bodyMedium
                            ?.copyWith(color: theme.colorScheme.outline)),
                  );
                }
                return Card(
                  child: ListView.separated(
                    itemCount: list.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, i) {
                      final inv = list[i];
                      return _InvitationRow(
                        invitation: inv,
                        canRevoke: _canManage,
                        onRevoke: () => _revoke(context, ref, inv),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _revoke(
      BuildContext context, WidgetRef ref, Invitation inv) async {
    try {
      await ref.read(membersControllerProvider).revoke(
            workspaceCode: workspaceCode,
            invitationId: inv.id,
          );
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Invitation revoked.')),
      );
    } on MembersApiException catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message)),
      );
    }
  }
}

class _InvitationRow extends StatelessWidget {
  const _InvitationRow({
    required this.invitation,
    required this.canRevoke,
    required this.onRevoke,
  });
  final Invitation invitation;
  final bool canRevoke;
  final VoidCallback onRevoke;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          Icon(Icons.mail_outline,
              size: 20, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(invitation.email,
                    style: theme.textTheme.bodyLarge,
                    overflow: TextOverflow.ellipsis),
                if (invitation.expiresAt != null)
                  Text('Expires ${invitation.expiresAt}',
                      style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.outline)),
              ],
            ),
          ),
          const SizedBox(width: 12),
          RoleChip(invitation.role),
          const SizedBox(width: 12),
          Tooltip(
            message: canRevoke ? 'Revoke invitation' : 'Requires ADMIN role.',
            child: IconButton(
              icon: const Icon(Icons.cancel_outlined),
              onPressed: canRevoke ? onRevoke : null,
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Danger zone — archive
// ---------------------------------------------------------------------------

class _DangerZoneTab extends ConsumerWidget {
  const _DangerZoneTab({required this.workspace, required this.role});
  final Workspace workspace;
  final String role;

  bool get _canArchive => role == 'OWNER' || role == 'PLATFORM_ADMIN';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.errorContainer.withValues(alpha: 0.3),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: theme.colorScheme.error),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.warning_amber,
                          color: theme.colorScheme.error),
                      const SizedBox(width: 8),
                      Text('Archive this workspace',
                          style: theme.textTheme.titleMedium),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Archiving hides the workspace from your list. Data is '
                    'preserved for audit; only a platform admin can restore '
                    'it afterwards. Only an OWNER can archive.',
                    style: theme.textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 16),
                  Align(
                    alignment: Alignment.centerRight,
                    child: Tooltip(
                      message: _canArchive ? '' : 'Requires OWNER role.',
                      child: FilledButton.icon(
                        style: FilledButton.styleFrom(
                            backgroundColor: theme.colorScheme.error),
                        icon: const Icon(Icons.archive_outlined),
                        label: const Text('Archive workspace'),
                        onPressed: _canArchive
                            ? () => _archive(context, ref)
                            : null,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _archive(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => ConfirmTypedNameDialog(
        title: 'Archive ${workspace.displayName}?',
        expectedName: workspace.code,
        confirmLabel: 'Archive',
        body: const Text(
          'This hides the workspace from your list. Data is preserved for audit. '
          'Only a platform admin can restore it afterwards.',
        ),
      ),
    );
    if (confirmed != true) return;
    try {
      final api = await ref.read(workspacesApiProvider.future);
      await api.archive(workspace.code, workspace.code);
      ref.invalidate(myWorkspacesProvider);
      if (!context.mounted) return;
      // Drop the now-archived workspace from current selection.
      final current = ref.read(currentWorkspaceOrNull);
      if (current == workspace.code) {
        await ref.read(currentWorkspaceProvider.notifier).switchTo('');
      }
      context.go('/workspaces');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Workspace "${workspace.code}" archived.')),
      );
    } on WorkspaceApiException catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message)),
      );
    }
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

bool _atLeast(String actor, String floor) {
  const tier = {
    'VIEWER': 0,
    'MEMBER': 1,
    'ADMIN': 2,
    'OWNER': 3,
    'PLATFORM_ADMIN': 4,
  };
  return (tier[actor] ?? -1) >= (tier[floor] ?? 0);
}

class _NoAccessView extends StatelessWidget {
  const _NoAccessView({required this.onBack});
  final VoidCallback onBack;
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.hourglass_disabled,
                size: 48, color: theme.colorScheme.outline),
            const SizedBox(height: 12),
            Text('Not available',
                style: theme.textTheme.headlineSmall),
            const SizedBox(height: 6),
            Text(
              "This workspace doesn't exist or you don't have access to it.",
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: onBack,
              child: const Text('Back to your workspaces'),
            ),
          ],
        ),
      ),
    );
  }
}

// Keep the auth import used (the screen could surface a Sign-in hint if
// the session evaporates mid-edit). Silences the unused-import lint that
// would fire if we drop the reference but keep the import for clarity.
// ignore: unused_element
AuthState _ensureAuthImport(WidgetRef r) => r.read(authProvider);
