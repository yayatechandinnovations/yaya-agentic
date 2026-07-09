import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../domain/entities/member.dart';
import '../bloc/users_bloc.dart';

/// Org members + lifecycle actions (lock / disable / delete) and invite. Delete
/// is a destructive, confirmed action — it scrubs PII (GDPR).
class UsersPage extends StatefulWidget {
  const UsersPage({super.key});

  @override
  State<UsersPage> createState() => _UsersPageState();
}

class _UsersPageState extends State<UsersPage> {
  final _org = TextEditingController();

  @override
  void dispose() {
    _org.dispose();
    super.dispose();
  }

  void _load(BuildContext context) =>
      context.read<UsersBloc>().add(MembersRequested(_org.text.trim()));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Users')),
      body: BlocConsumer<UsersBloc, UsersState>(
        listenWhen: (p, c) => c.inviteToken != null && p.inviteToken != c.inviteToken,
        listener: (context, state) => _showInvite(context, state.inviteToken!),
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
                  onPressed: state.org.isEmpty ? null : () => _promptInvite(context, state.org),
                  icon: const Icon(Icons.mail),
                  label: const Text('Invite'),
                ),
              ]),
            ),
            Expanded(child: _body(context, state)),
          ],
        ),
      ),
    );
  }

  Widget _body(BuildContext context, UsersState state) {
    switch (state.status) {
      case UsersStatus.initial:
        return const Center(child: Text('Enter an org id and load its members.'));
      case UsersStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case UsersStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case UsersStatus.loaded:
        if (state.members.isEmpty) return const Center(child: Text('No members.'));
        return ListView.separated(
          itemCount: state.members.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (_, i) => _MemberTile(member: state.members[i], org: state.org),
        );
    }
  }

  Future<void> _promptInvite(BuildContext context, String org) async {
    final email = TextEditingController();
    final bloc = context.read<UsersBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Invite user'),
        content: TextField(controller: email, decoration: const InputDecoration(labelText: 'Email')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Invite')),
        ],
      ),
    );
    if (ok == true && email.text.trim().isNotEmpty) {
      bloc.add(UserInviteRequested(org, email.text.trim()));
    }
  }

  void _showInvite(BuildContext context, String token) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Invitation created'),
        content: SelectableText('Deliver this one-time token to the invitee:\n\n$token'),
        actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Done'))],
      ),
    );
  }
}

class _MemberTile extends StatelessWidget {
  const _MemberTile({required this.member, required this.org});

  final Member member;
  final String org;

  @override
  Widget build(BuildContext context) {
    final bloc = context.read<UsersBloc>();
    return ListTile(
      leading: const Icon(Icons.person),
      title: Text(member.userId),
      subtitle: Text(member.status),
      trailing: PopupMenuButton<String>(
        onSelected: (v) async {
          switch (v) {
            case 'lock':
              bloc.add(UserLockRequested(org, member.userId));
            case 'disable':
              bloc.add(UserDisableRequested(org, member.userId));
            case 'delete':
              final confirm = await _confirmDelete(context, member.userId);
              if (confirm) bloc.add(UserDeleteRequested(org, member.userId));
          }
        },
        itemBuilder: (_) => const [
          PopupMenuItem(value: 'lock', child: Text('Lock')),
          PopupMenuItem(value: 'disable', child: Text('Disable')),
          PopupMenuItem(value: 'delete', child: Text('Delete (scrub PII)')),
        ],
      ),
    );
  }

  Future<bool> _confirmDelete(BuildContext context, String userId) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete user?'),
        content: Text('This scrubs PII for $userId and cannot be undone. The audit history is kept.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    return ok ?? false;
  }
}
