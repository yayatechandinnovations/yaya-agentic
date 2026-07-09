import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../domain/entities/group.dart';
import '../bloc/groups_bloc.dart';

/// Groups — bulk role targeting. List/create groups in an org and add members;
/// a role assigned to a group applies to every member.
class GroupsPage extends StatefulWidget {
  const GroupsPage({super.key});

  @override
  State<GroupsPage> createState() => _GroupsPageState();
}

class _GroupsPageState extends State<GroupsPage> {
  final _org = TextEditingController();

  @override
  void dispose() {
    _org.dispose();
    super.dispose();
  }

  void _load(BuildContext context) =>
      context.read<GroupsBloc>().add(GroupsRequested(_org.text.trim()));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Groups')),
      body: BlocConsumer<GroupsBloc, GroupsState>(
        listenWhen: (p, c) => c.message != null && p.message != c.message,
        listener: (context, state) =>
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(state.message!))),
        builder: (context, state) => Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Row(children: [
                Expanded(
                  child: TextField(
                    controller: _org,
                    decoration: const InputDecoration(labelText: 'Organization id'),
                    onSubmitted: (_) => _load(context),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton.icon(
                    onPressed: () => _load(context), icon: const Icon(Icons.search), label: const Text('Load')),
                const SizedBox(width: 12),
                OutlinedButton.icon(
                  onPressed: state.org.isEmpty ? null : () => _promptCreate(context, state.org),
                  icon: const Icon(Icons.group_add),
                  label: const Text('New group'),
                ),
              ]),
            ),
            Expanded(child: _body(context, state)),
          ],
        ),
      ),
    );
  }

  Widget _body(BuildContext context, GroupsState state) {
    switch (state.status) {
      case GroupsStatus.initial:
        return const Center(child: Text('Enter an org id and load its groups.'));
      case GroupsStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case GroupsStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case GroupsStatus.loaded:
        if (state.groups.isEmpty) return const Center(child: Text('No groups in this org yet.'));
        return ListView.separated(
          itemCount: state.groups.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (_, i) {
            final group = state.groups[i];
            return ListTile(
              leading: const Icon(Icons.groups),
              title: Text(group.name),
              subtitle: Text(group.id),
              trailing: TextButton.icon(
                icon: const Icon(Icons.person_add),
                label: const Text('Add member'),
                onPressed: () => _promptAddMember(context, group),
              ),
            );
          },
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
        content: TextField(controller: name, decoration: const InputDecoration(labelText: 'Group name')),
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

  Future<void> _promptAddMember(BuildContext context, Group group) async {
    final userId = TextEditingController();
    final bloc = context.read<GroupsBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Add member to "${group.name}"'),
        content: TextField(controller: userId, decoration: const InputDecoration(labelText: 'User id')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
        ],
      ),
    );
    if (ok == true && userId.text.trim().isNotEmpty) {
      bloc.add(GroupMemberAddRequested(group.id, userId.text.trim()));
    }
  }
}
