import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../../../users/domain/entities/member.dart';
import '../../domain/entities/group.dart';
import '../bloc/groups_bloc.dart';

/// Groups — bulk role targeting. Each group expands into its member list;
/// adding a member picks from the org's members (no raw user ids).
class GroupsPage extends StatelessWidget {
  const GroupsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Groups'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) => context.read<GroupsBloc>().add(GroupsRequested(orgId)),
        builder: (context, orgId) => BlocConsumer<GroupsBloc, GroupsState>(
          listenWhen: (p, c) => c.message != null && p.message != c.message,
          listener: (context, state) => ScaffoldMessenger.of(context)
              .showSnackBar(SnackBar(content: Text(state.message!))),
          builder: (context, state) => Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
                child: Row(children: [
                  Text('${state.groups.length} group${state.groups.length == 1 ? '' : 's'}',
                      style: const TextStyle(color: Colors.black54)),
                  const Spacer(),
                  FilledButton.icon(
                    onPressed: () => _promptCreate(context, orgId),
                    icon: const Icon(Icons.group_add),
                    label: const Text('New group'),
                  ),
                ]),
              ),
              Expanded(child: _body(context, state, orgId)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _body(BuildContext context, GroupsState state, String orgId) {
    switch (state.status) {
      case GroupsStatus.initial:
      case GroupsStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case GroupsStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case GroupsStatus.loaded:
        if (state.groups.isEmpty) {
          return const Center(child: Text('No groups yet — create the first one.'));
        }
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          itemCount: state.groups.length,
          itemBuilder: (_, i) => _GroupCard(group: state.groups[i], org: orgId, state: state),
        );
    }
  }

  Future<void> _promptCreate(BuildContext context, String org) async {
    final name = TextEditingController();
    final bloc = context.read<GroupsBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New group'),
        content: TextField(
          controller: name,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Group name'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
        ],
      ),
    );
    if (ok == true && name.text.trim().isNotEmpty) {
      bloc.add(GroupCreateRequested(org, name.text.trim()));
    }
  }
}

class _GroupCard extends StatelessWidget {
  const _GroupCard({required this.group, required this.org, required this.state});

  final Group group;
  final String org;
  final GroupsState state;

  @override
  Widget build(BuildContext context) {
    final members = state.groupMembers[group.id];
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        leading: const Icon(Icons.groups, color: AppBrand.violet),
        shape: const Border(),
        title: Text(group.name, style: const TextStyle(fontWeight: FontWeight.w500)),
        subtitle: Text('${group.memberCount} member${group.memberCount == 1 ? '' : 's'}',
            style: const TextStyle(fontSize: 12)),
        onExpansionChanged: (open) {
          if (open && members == null) {
            context.read<GroupsBloc>().add(GroupMembersRequested(group.id));
          }
        },
        children: [
          if (members == null)
            const Padding(
              padding: EdgeInsets.all(16),
              child: Center(child: CircularProgressIndicator()),
            )
          else ...[
            if (members.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Text('No members in this group yet.',
                    style: TextStyle(color: Colors.black54)),
              )
            else
              ...members.map((m) => ListTile(
                    dense: true,
                    leading: CircleAvatar(
                      radius: 14,
                      backgroundColor: AppBrand.cyan.withValues(alpha: 0.15),
                      foregroundColor: AppBrand.navy,
                      child: Text(m.initials, style: const TextStyle(fontSize: 11)),
                    ),
                    title: Text(m.label),
                    subtitle: (m.email ?? '').isNotEmpty && m.email != m.label
                        ? Text(m.email!)
                        : null,
                    trailing: IconButton(
                      tooltip: 'Remove from group',
                      icon: const Icon(Icons.person_remove_outlined, size: 18),
                      onPressed: () => context
                          .read<GroupsBloc>()
                          .add(GroupMemberRemoveRequested(org, group.id, m.userId)),
                    ),
                  )),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  icon: const Icon(Icons.person_add, size: 18),
                  label: const Text('Add member'),
                  onPressed: () => _promptAddMember(context, members),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _promptAddMember(BuildContext context, List<Member> current) async {
    final bloc = context.read<GroupsBloc>();
    final inGroup = current.map((m) => m.userId).toSet();
    final candidates =
        bloc.state.orgMembers.where((m) => !inGroup.contains(m.userId)).toList();
    String? userId;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text('Add member to "${group.name}"'),
          content: SizedBox(
            width: 380,
            child: candidates.isEmpty
                ? const Text('Every org member is already in this group.')
                : DropdownButtonFormField<String>(
                    initialValue: userId,
                    decoration: const InputDecoration(labelText: 'Member'),
                    items: candidates
                        .map((m) => DropdownMenuItem(
                              value: m.userId,
                              child: Text(
                                (m.email ?? '').isNotEmpty && m.email != m.label
                                    ? '${m.label} (${m.email})'
                                    : m.label,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ))
                        .toList(),
                    onChanged: (v) => setLocal(() => userId = v),
                  ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
              onPressed:
                  candidates.isEmpty || userId == null ? null : () => Navigator.pop(ctx, true),
              child: const Text('Add'),
            ),
          ],
        ),
      ),
    );
    if (ok == true && userId != null) {
      bloc.add(GroupMemberAddRequested(org, group.id, userId!));
    }
  }
}
