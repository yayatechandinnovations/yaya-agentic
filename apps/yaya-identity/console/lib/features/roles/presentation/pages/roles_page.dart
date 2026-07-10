import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../../../../core/widgets/permission_picker.dart';
import '../../domain/entities/role.dart';
import '../../domain/entities/role_assignment_request.dart';
import '../bloc/roles_bloc.dart';

/// Roles & scoped assignments — the load-bearing RBAC screen. Roles show their
/// permissions as chips; composing a role picks from the declared permission
/// catalog; assigning picks the user or group from a dropdown (no raw ids).
class RolesPage extends StatelessWidget {
  const RolesPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Roles & assignments'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) => context.read<RolesBloc>().add(RolesRequested(orgId)),
        builder: (context, orgId) => BlocConsumer<RolesBloc, RolesState>(
          listenWhen: (p, c) => c.message != null && p.message != c.message,
          listener: (context, state) => ScaffoldMessenger.of(context)
              .showSnackBar(SnackBar(content: Text(state.message!))),
          builder: (context, state) => Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
                child: Row(children: [
                  Text('${state.roles.length} role${state.roles.length == 1 ? '' : 's'}',
                      style: const TextStyle(color: Colors.black54)),
                  const Spacer(),
                  FilledButton.icon(
                    onPressed: () => _promptCreate(context, orgId),
                    icon: const Icon(Icons.add),
                    label: const Text('New role'),
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

  Widget _body(BuildContext context, RolesState state) {
    switch (state.status) {
      case RolesStatus.initial:
      case RolesStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case RolesStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case RolesStatus.loaded:
        if (state.roles.isEmpty) return const Center(child: Text('No roles yet.'));
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          itemCount: state.roles.length,
          itemBuilder: (_, i) => _RoleCard(role: state.roles[i], org: state.org),
        );
    }
  }

  Future<void> _promptCreate(BuildContext context, String org) async {
    final bloc = context.read<RolesBloc>();
    final catalog = bloc.state.catalog;
    final name = TextEditingController();
    final selected = <String>{};

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('New role'),
          content: SizedBox(
            width: 480,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: name,
                  autofocus: true,
                  decoration: const InputDecoration(labelText: 'Role name'),
                ),
                const SizedBox(height: 16),
                const Text('Permissions', style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 8),
                PermissionPicker(
                  catalog: catalog,
                  selected: selected,
                  onChanged: (next) => setLocal(() {
                    selected
                      ..clear()
                      ..addAll(next);
                  }),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
          ],
        ),
      ),
    );
    if (ok == true && name.text.trim().isNotEmpty) {
      bloc.add(RoleCreateRequested(org, name.text.trim(), selected.toList()..sort()));
    }
  }
}

class _RoleCard extends StatelessWidget {
  const _RoleCard({required this.role, required this.org});

  final Role role;
  final String org;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(role.system ? Icons.verified_user : Icons.badge,
                color: role.system ? AppBrand.cyan : AppBrand.violet),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Text(role.name,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                    if (role.system) ...[
                      const SizedBox(width: 8),
                      const Chip(
                        label: Text('built-in'),
                        visualDensity: VisualDensity.compact,
                        labelStyle: TextStyle(fontSize: 11),
                      ),
                    ],
                  ]),
                  if (role.permissions.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 6,
                      runSpacing: 6,
                      children: role.permissions
                          .map((p) => Chip(
                                label: Text(p, style: const TextStyle(fontSize: 11)),
                                visualDensity: VisualDensity.compact,
                              ))
                          .toList(),
                    ),
                  ] else if (role.system) ...[
                    const SizedBox(height: 4),
                    const Text('Platform-defined capabilities',
                        style: TextStyle(fontSize: 12, color: Colors.black45)),
                  ],
                ],
              ),
            ),
            TextButton.icon(
              icon: const Icon(Icons.person_add, size: 18),
              label: const Text('Assign'),
              onPressed: () => _promptAssign(context, org, role),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _promptAssign(BuildContext context, String org, Role role) async {
    final bloc = context.read<RolesBloc>();
    final members = bloc.state.members;
    final groups = bloc.state.groups;
    final scopeType = TextEditingController();
    final scopeId = TextEditingController();
    var subjectKind = 'USER';
    String? subjectId;

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text('Assign "${role.name}"'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'USER', icon: Icon(Icons.person), label: Text('User')),
                    ButtonSegment(value: 'GROUP', icon: Icon(Icons.groups), label: Text('Group')),
                  ],
                  selected: {subjectKind},
                  onSelectionChanged: (s) => setLocal(() {
                    subjectKind = s.first;
                    subjectId = null;
                  }),
                ),
                const SizedBox(height: 16),
                if (subjectKind == 'USER')
                  DropdownButtonFormField<String>(
                    initialValue: subjectId,
                    decoration: const InputDecoration(labelText: 'User'),
                    items: members
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
                    onChanged: (v) => setLocal(() => subjectId = v),
                  )
                else
                  DropdownButtonFormField<String>(
                    initialValue: subjectId,
                    decoration: const InputDecoration(labelText: 'Group'),
                    items: groups
                        .map((g) => DropdownMenuItem(value: g.id, child: Text(g.name)))
                        .toList(),
                    onChanged: (v) => setLocal(() => subjectId = v),
                  ),
                const SizedBox(height: 16),
                const Text('Scope — leave blank for org-wide',
                    style: TextStyle(fontSize: 12, color: Colors.black54)),
                const SizedBox(height: 8),
                Row(children: [
                  Expanded(
                    child: TextField(
                      controller: scopeType,
                      decoration: const InputDecoration(
                          labelText: 'Resource type', hintText: 'store'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextField(
                      controller: scopeId,
                      decoration: const InputDecoration(
                          labelText: 'Resource id', hintText: 'st_123'),
                    ),
                  ),
                ]),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
              onPressed: subjectId == null ? null : () => Navigator.pop(ctx, true),
              child: const Text('Assign'),
            ),
          ],
        ),
      ),
    );
    if (ok == true && subjectId != null) {
      bloc.add(RoleAssignRequested(RoleAssignmentRequest(
        org: org,
        subjectKind: subjectKind,
        subjectId: subjectId!,
        roleId: role.id,
        scopeType: scopeType.text.trim(),
        scopeId: scopeId.text.trim(),
      )));
    }
  }
}
