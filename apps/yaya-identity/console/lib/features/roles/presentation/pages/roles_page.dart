import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../domain/entities/role.dart';
import '../../domain/entities/role_assignment_request.dart';
import '../bloc/roles_bloc.dart';

/// Roles & scoped assignments — the load-bearing RBAC screen. List/create roles
/// for an org, and grant a role to a subject within a scope (org-wide or one
/// resource like `store:st_123`).
class RolesPage extends StatefulWidget {
  const RolesPage({super.key});

  @override
  State<RolesPage> createState() => _RolesPageState();
}

class _RolesPageState extends State<RolesPage> {
  final _org = TextEditingController();

  @override
  void dispose() {
    _org.dispose();
    super.dispose();
  }

  void _load(BuildContext context) =>
      context.read<RolesBloc>().add(RolesRequested(_org.text.trim()));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Roles & assignments')),
      body: BlocConsumer<RolesBloc, RolesState>(
        listenWhen: (p, c) => c.message != null && p.message != c.message,
        listener: (context, state) {
          if (state.message != null) {
            ScaffoldMessenger.of(context)
                .showSnackBar(SnackBar(content: Text(state.message!)));
          }
        },
        builder: (context, state) {
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _org,
                        decoration: const InputDecoration(labelText: 'Organization id'),
                        onSubmitted: (_) => _load(context),
                      ),
                    ),
                    const SizedBox(width: 12),
                    FilledButton.icon(
                      onPressed: () => _load(context),
                      icon: const Icon(Icons.search),
                      label: const Text('Load'),
                    ),
                    const SizedBox(width: 12),
                    OutlinedButton.icon(
                      onPressed: state.org.isEmpty ? null : () => _promptCreate(context, state.org),
                      icon: const Icon(Icons.add),
                      label: const Text('New role'),
                    ),
                  ],
                ),
              ),
              Expanded(child: _body(context, state)),
            ],
          );
        },
      ),
    );
  }

  Widget _body(BuildContext context, RolesState state) {
    switch (state.status) {
      case RolesStatus.initial:
        return const Center(child: Text('Enter an org id and load its roles.'));
      case RolesStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case RolesStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case RolesStatus.loaded:
        if (state.roles.isEmpty) return const Center(child: Text('No roles in this org yet.'));
        return ListView.separated(
          itemCount: state.roles.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (_, i) {
            final role = state.roles[i];
            return ListTile(
              leading: const Icon(Icons.badge),
              title: Text(role.name),
              subtitle: Text(role.id),
              trailing: TextButton.icon(
                icon: const Icon(Icons.person_add),
                label: const Text('Assign'),
                onPressed: () => _promptAssign(context, state.org, role),
              ),
            );
          },
        );
    }
  }

  Future<void> _promptCreate(BuildContext context, String org) async {
    final name = TextEditingController();
    final perms = TextEditingController();
    final bloc = context.read<RolesBloc>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New role'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: name, decoration: const InputDecoration(labelText: 'Role name')),
            TextField(
              controller: perms,
              decoration: const InputDecoration(
                labelText: 'Permissions (comma-separated)',
                hintText: 'sales.order:read, sales.order:refund',
              ),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
        ],
      ),
    );
    if (ok == true && name.text.trim().isNotEmpty) {
      final permissions = perms.text
          .split(',')
          .map((s) => s.trim())
          .where((s) => s.isNotEmpty)
          .toList();
      bloc.add(RoleCreateRequested(org, name.text.trim(), permissions));
    }
  }

  Future<void> _promptAssign(BuildContext context, String org, Role role) async {
    final subjectId = TextEditingController();
    final scopeType = TextEditingController();
    final scopeId = TextEditingController();
    var subjectKind = 'USER';
    final bloc = context.read<RolesBloc>();

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text('Assign "${role.name}"'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: subjectKind,
                decoration: const InputDecoration(labelText: 'Subject kind'),
                items: const [
                  DropdownMenuItem(value: 'USER', child: Text('User')),
                  DropdownMenuItem(value: 'GROUP', child: Text('Group')),
                ],
                onChanged: (v) => setLocal(() => subjectKind = v ?? 'USER'),
              ),
              TextField(controller: subjectId, decoration: const InputDecoration(labelText: 'Subject id')),
              const SizedBox(height: 8),
              const Align(
                alignment: Alignment.centerLeft,
                child: Text('Scope — leave blank for org-wide', style: TextStyle(fontSize: 12)),
              ),
              Row(children: [
                Expanded(child: TextField(controller: scopeType, decoration: const InputDecoration(labelText: 'Resource type'))),
                const SizedBox(width: 8),
                Expanded(child: TextField(controller: scopeId, decoration: const InputDecoration(labelText: 'Resource id'))),
              ]),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Assign')),
          ],
        ),
      ),
    );
    if (ok == true && subjectId.text.trim().isNotEmpty) {
      bloc.add(RoleAssignRequested(RoleAssignmentRequest(
        org: org,
        subjectKind: subjectKind,
        subjectId: subjectId.text.trim(),
        roleId: role.id,
        scopeType: scopeType.text.trim(),
        scopeId: scopeId.text.trim(),
      )));
    }
  }
}
