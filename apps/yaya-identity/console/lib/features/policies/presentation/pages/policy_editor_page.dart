import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../domain/entities/policy.dart';
import '../../domain/entities/policy_draft.dart';
import '../bloc/policy_editor_bloc.dart';

/// Policy editor — list an org's policies and author a new one (effect, target,
/// JSON-logic condition, priority). A DENY requires two distinct reasons (#3),
/// enforced by the backend and surfaced here.
class PolicyEditorPage extends StatefulWidget {
  const PolicyEditorPage({super.key});

  @override
  State<PolicyEditorPage> createState() => _PolicyEditorPageState();
}

class _PolicyEditorPageState extends State<PolicyEditorPage> {
  final _org = TextEditingController();

  @override
  void dispose() {
    _org.dispose();
    super.dispose();
  }

  void _load(BuildContext context) =>
      context.read<PolicyEditorBloc>().add(PoliciesRequested(_org.text.trim()));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Policies')),
      body: BlocConsumer<PolicyEditorBloc, PolicyEditorState>(
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
                  icon: const Icon(Icons.post_add),
                  label: const Text('New policy'),
                ),
              ]),
            ),
            Expanded(child: _body(state)),
          ],
        ),
      ),
    );
  }

  Widget _body(PolicyEditorState state) {
    switch (state.status) {
      case PolicyEditorStatus.initial:
        return const Center(child: Text('Enter an org id and load its policies.'));
      case PolicyEditorStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case PolicyEditorStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case PolicyEditorStatus.loaded:
        if (state.policies.isEmpty) return const Center(child: Text('No policies in this org yet.'));
        return ListView.separated(
          itemCount: state.policies.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (_, i) {
            final Policy p = state.policies[i];
            return ListTile(
              leading: Icon(p.effect == 'DENY' ? Icons.block : Icons.check_circle,
                  color: p.effect == 'DENY' ? Colors.red : Colors.green),
              title: Text(p.name),
              subtitle: Text('${p.effect} · priority ${p.priority}'),
            );
          },
        );
    }
  }

  Future<void> _promptCreate(BuildContext context, String org) async {
    final name = TextEditingController();
    final target = TextEditingController(text: '{"actions":["sales.order:refund"]}');
    final condition = TextEditingController();
    final priority = TextEditingController(text: '0');
    final userReason = TextEditingController();
    final auditReason = TextEditingController();
    var effect = 'DENY';
    final bloc = context.read<PolicyEditorBloc>();

    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('New policy'),
          content: SizedBox(
            width: 520,
            child: SingleChildScrollView(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                TextField(controller: name, decoration: const InputDecoration(labelText: 'Name')),
                DropdownButtonFormField<String>(
                  initialValue: effect,
                  decoration: const InputDecoration(labelText: 'Effect'),
                  items: const [
                    DropdownMenuItem(value: 'PERMIT', child: Text('PERMIT')),
                    DropdownMenuItem(value: 'DENY', child: Text('DENY')),
                  ],
                  onChanged: (v) => setLocal(() => effect = v ?? 'DENY'),
                ),
                TextField(
                  controller: target,
                  decoration: const InputDecoration(labelText: 'Target (JSON)'),
                  maxLines: 2,
                ),
                TextField(
                  controller: condition,
                  decoration: const InputDecoration(
                    labelText: 'Condition (JSON-logic, optional)',
                    hintText: '{">":[{"var":"resource.amount"},{"var":"subject.refundLimit"}]}',
                  ),
                  maxLines: 2,
                ),
                TextField(controller: priority, decoration: const InputDecoration(labelText: 'Priority'),
                    keyboardType: TextInputType.number),
                if (effect == 'DENY') ...[
                  const Divider(),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('DENY needs two distinct reasons (#3)', style: TextStyle(fontSize: 12)),
                  ),
                  TextField(controller: userReason,
                      decoration: const InputDecoration(labelText: 'User-safe reason')),
                  TextField(controller: auditReason,
                      decoration: const InputDecoration(labelText: 'Audit reason')),
                ],
              ]),
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
      bloc.add(PolicyCreateRequested(PolicyDraft(
        org: org,
        name: name.text.trim(),
        effect: effect,
        targetJson: target.text.trim(),
        conditionJson: condition.text.trim(),
        priority: int.tryParse(priority.text.trim()) ?? 0,
        userReason: effect == 'DENY' ? userReason.text.trim() : null,
        auditReason: effect == 'DENY' ? auditReason.text.trim() : null,
      )));
    }
  }
}
