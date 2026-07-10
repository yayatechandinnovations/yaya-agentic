import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../../domain/entities/policy.dart';
import '../../domain/entities/policy_draft.dart';
import '../bloc/policy_editor_bloc.dart';

/// Policy editor — list an org's policies and author a new one. The target is
/// composed by picking actions from the declared catalog (no hand-written
/// JSON); the JSON-logic condition stays an advanced, optional field. A DENY
/// requires two distinct reasons (#3), enforced by the backend and surfaced here.
class PolicyEditorPage extends StatelessWidget {
  const PolicyEditorPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Policies'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) =>
            context.read<PolicyEditorBloc>().add(PoliciesRequested(orgId)),
        builder: (context, orgId) => BlocConsumer<PolicyEditorBloc, PolicyEditorState>(
          listenWhen: (p, c) => c.message != null && p.message != c.message,
          listener: (context, state) => ScaffoldMessenger.of(context)
              .showSnackBar(SnackBar(content: Text(state.message!))),
          builder: (context, state) => Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(24, 8, 24, 8),
                child: Row(children: [
                  Text('${state.policies.length} '
                      'polic${state.policies.length == 1 ? 'y' : 'ies'}',
                      style: const TextStyle(color: Colors.black54)),
                  const Spacer(),
                  FilledButton.icon(
                    onPressed: () => _promptCreate(context, orgId),
                    icon: const Icon(Icons.post_add),
                    label: const Text('New policy'),
                  ),
                ]),
              ),
              Expanded(child: _body(state)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _body(PolicyEditorState state) {
    switch (state.status) {
      case PolicyEditorStatus.initial:
      case PolicyEditorStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case PolicyEditorStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case PolicyEditorStatus.loaded:
        if (state.policies.isEmpty) return const Center(child: Text('No policies yet.'));
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          itemCount: state.policies.length,
          itemBuilder: (_, i) {
            final Policy p = state.policies[i];
            final deny = p.effect == 'DENY';
            return Card(
              margin: const EdgeInsets.symmetric(vertical: 4),
              child: ListTile(
                leading: Icon(deny ? Icons.block : Icons.check_circle,
                    color: deny ? Colors.red : Colors.green),
                title: Text(p.name, style: const TextStyle(fontWeight: FontWeight.w500)),
                subtitle: Text('${p.effect} · priority ${p.priority}'),
              ),
            );
          },
        );
    }
  }

  Future<void> _promptCreate(BuildContext context, String org) async {
    final bloc = context.read<PolicyEditorBloc>();
    final catalog = bloc.state.catalog;
    final name = TextEditingController();
    final condition = TextEditingController();
    final priority = TextEditingController(text: '0');
    final userReason = TextEditingController();
    final auditReason = TextEditingController();
    final selectedActions = <String>{};
    var effect = 'DENY';

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('New policy'),
          content: SizedBox(
            width: 540,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextField(
                      controller: name,
                      autofocus: true,
                      decoration: const InputDecoration(labelText: 'Name')),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: effect,
                    decoration: const InputDecoration(labelText: 'Effect'),
                    items: const [
                      DropdownMenuItem(value: 'PERMIT', child: Text('PERMIT')),
                      DropdownMenuItem(value: 'DENY', child: Text('DENY')),
                    ],
                    onChanged: (v) => setLocal(() => effect = v ?? 'DENY'),
                  ),
                  const SizedBox(height: 16),
                  const Text('Applies to actions',
                      style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 8),
                  if (catalog.isEmpty)
                    const Text('No declared actions yet — the policy will need a '
                        'manifest-declared permission catalog to target.',
                        style: TextStyle(color: Colors.black54, fontSize: 13))
                  else
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 180),
                      child: SingleChildScrollView(
                        child: Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: catalog
                              .map((a) => FilterChip(
                                    label: Text(a, style: const TextStyle(fontSize: 12)),
                                    selected: selectedActions.contains(a),
                                    onSelected: (on) => setLocal(() {
                                      if (on) {
                                        selectedActions.add(a);
                                      } else {
                                        selectedActions.remove(a);
                                      }
                                    }),
                                  ))
                              .toList(),
                        ),
                      ),
                    ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: condition,
                    decoration: const InputDecoration(
                      labelText: 'Condition (JSON-logic, optional — advanced)',
                      hintText: '{">":[{"var":"resource.amount"},{"var":"subject.refundLimit"}]}',
                    ),
                    maxLines: 2,
                  ),
                  const SizedBox(height: 12),
                  TextField(
                      controller: priority,
                      decoration: const InputDecoration(labelText: 'Priority'),
                      keyboardType: TextInputType.number),
                  if (effect == 'DENY') ...[
                    const Divider(height: 28),
                    const Text('A DENY carries two distinct reasons',
                        style: TextStyle(fontSize: 12, color: Colors.black54)),
                    const SizedBox(height: 8),
                    TextField(
                        controller: userReason,
                        decoration: const InputDecoration(
                            labelText: 'User-safe reason (what the app shows)')),
                    const SizedBox(height: 12),
                    TextField(
                        controller: auditReason,
                        decoration: const InputDecoration(
                            labelText: 'Audit reason (operators only)')),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Create'),
            ),
          ],
        ),
      ),
    );

    if (ok == true && name.text.trim().isNotEmpty && selectedActions.isNotEmpty) {
      bloc.add(PolicyCreateRequested(PolicyDraft(
        org: org,
        name: name.text.trim(),
        effect: effect,
        targetJson: jsonEncode({'actions': selectedActions.toList()..sort()}),
        conditionJson: condition.text.trim(),
        priority: int.tryParse(priority.text.trim()) ?? 0,
        userReason: effect == 'DENY' ? userReason.text.trim() : null,
        auditReason: effect == 'DENY' ? auditReason.text.trim() : null,
      )));
    }
  }
}
