import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../../domain/entities/member.dart';
import '../bloc/users_bloc.dart';

/// Org members + lifecycle actions (lock / disable / delete) and invite. The
/// org comes from the console-wide scope picker; members are shown as people
/// (name, email, status), never as raw ids. Delete is a destructive, confirmed
/// action — it scrubs PII (GDPR).
class UsersPage extends StatelessWidget {
  const UsersPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Users'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) => context.read<UsersBloc>().add(MembersRequested(orgId)),
        builder: (context, orgId) => BlocConsumer<UsersBloc, UsersState>(
          listenWhen: (p, c) => c.inviteToken != null && p.inviteToken != c.inviteToken,
          listener: (context, state) => _showInvite(context, state.inviteToken!),
          builder: (context, state) => Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
                child: Row(children: [
                  Text('${state.members.length} member${state.members.length == 1 ? '' : 's'}',
                      style: const TextStyle(color: Colors.black54)),
                  const Spacer(),
                  FilledButton.icon(
                    onPressed: () => _promptInvite(context, orgId),
                    icon: const Icon(Icons.person_add_alt),
                    label: const Text('Invite user'),
                  ),
                ]),
              ),
              Expanded(child: _body(context, state)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _body(BuildContext context, UsersState state) {
    switch (state.status) {
      case UsersStatus.initial:
      case UsersStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case UsersStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case UsersStatus.loaded:
        if (state.members.isEmpty) {
          return const Center(child: Text('No members yet — invite the first one.'));
        }
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          itemCount: state.members.length,
          itemBuilder: (_, i) => _MemberCard(member: state.members[i], org: state.org),
        );
    }
  }

  Future<void> _promptInvite(BuildContext context, String org) async {
    final bloc = context.read<UsersBloc>();
    final email = TextEditingController();
    final roles = bloc.state.roles;
    String? role = roles.any((r) => r.name == 'member') ? 'member' : null;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('Invite user'),
          content: SizedBox(
            width: 380,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: email,
                  autofocus: true,
                  decoration: const InputDecoration(labelText: 'Email'),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: role,
                  decoration: const InputDecoration(labelText: 'Role'),
                  items: roles
                      .map((r) => DropdownMenuItem(
                            value: r.name,
                            child: Row(mainAxisSize: MainAxisSize.min, children: [
                              Text(r.name),
                              if (r.system) ...[
                                const SizedBox(width: 6),
                                const Text('built-in',
                                    style: TextStyle(fontSize: 11, color: Colors.black45)),
                              ],
                            ]),
                          ))
                      .toList(),
                  onChanged: (v) => setLocal(() => role = v),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Invite')),
          ],
        ),
      ),
    );
    if (ok == true && email.text.trim().isNotEmpty) {
      bloc.add(UserInviteRequested(org, email.text.trim(), role: role));
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

class _MemberCard extends StatelessWidget {
  const _MemberCard({required this.member, required this.org});

  final Member member;
  final String org;

  Color get _statusColor => switch (member.userStatus ?? member.status) {
        'ACTIVE' => Colors.green,
        'INVITED' => Colors.blue,
        'LOCKED' => Colors.orange,
        'DISABLED' || 'SUSPENDED' => Colors.red,
        _ => Colors.grey,
      };

  @override
  Widget build(BuildContext context) {
    final bloc = context.read<UsersBloc>();
    final status = member.userStatus ?? member.status;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: AppBrand.violet.withValues(alpha: 0.12),
          foregroundColor: AppBrand.violet,
          child: member.kind == 'SERVICE'
              ? const Icon(Icons.smart_toy, size: 20)
              : Text(member.initials, style: const TextStyle(fontWeight: FontWeight.w600)),
        ),
        title: Text(member.label, style: const TextStyle(fontWeight: FontWeight.w500)),
        subtitle: Text(
          [if ((member.email ?? '').isNotEmpty && member.email != member.label) member.email!,
           member.userId].join('  ·  '),
          style: const TextStyle(fontSize: 12),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Chip(
              label: Text(status),
              labelStyle: TextStyle(fontSize: 11, color: _statusColor, fontWeight: FontWeight.w600),
              side: BorderSide(color: _statusColor.withValues(alpha: 0.4)),
              backgroundColor: _statusColor.withValues(alpha: 0.08),
              visualDensity: VisualDensity.compact,
            ),
            const SizedBox(width: 4),
            PopupMenuButton<String>(
              onSelected: (v) async {
                switch (v) {
                  case 'lock':
                    bloc.add(UserLockRequested(org, member.userId));
                  case 'disable':
                    bloc.add(UserDisableRequested(org, member.userId));
                  case 'delete':
                    final confirm = await _confirmDelete(context, member.label);
                    if (confirm) bloc.add(UserDeleteRequested(org, member.userId));
                }
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'lock', child: Text('Lock')),
                PopupMenuItem(value: 'disable', child: Text('Disable')),
                PopupMenuItem(value: 'delete', child: Text('Delete (scrub PII)')),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<bool> _confirmDelete(BuildContext context, String who) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete user?'),
        content: Text('This scrubs PII for $who and cannot be undone. The audit history is kept.'),
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
