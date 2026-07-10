import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../../../../core/widgets/permission_picker.dart';
import '../../domain/entities/condition_builder.dart';
import '../../domain/entities/policy.dart';
import '../../domain/entities/policy_draft.dart';
import '../bloc/policy_editor_bloc.dart';

/// Policies — readable cards (effect, targeted actions, condition, reasons,
/// active toggle) and a guided builder: start from a template, pick actions by
/// module, and compose conditions as structured rows (attribute · operator ·
/// value) that compile to JSON-logic. Raw JSON stays available behind an
/// "advanced" switch. A DENY requires two distinct reasons (#3).
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
                    onPressed: () => _openBuilder(context, orgId),
                    icon: const Icon(Icons.post_add),
                    label: const Text('New policy'),
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

  Widget _body(BuildContext context, PolicyEditorState state, String orgId) {
    switch (state.status) {
      case PolicyEditorStatus.initial:
      case PolicyEditorStatus.loading:
        return const Center(child: CircularProgressIndicator());
      case PolicyEditorStatus.failure:
        return Center(child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')));
      case PolicyEditorStatus.loaded:
        if (state.policies.isEmpty) {
          return const Center(
              child: Text('No policies yet — roles decide who CAN act; '
                  'policies add the fine-grained exceptions.'));
        }
        return ListView.builder(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          itemCount: state.policies.length,
          itemBuilder: (_, i) => _PolicyCard(policy: state.policies[i], org: orgId),
        );
    }
  }

  Future<void> _openBuilder(BuildContext context, String org) async {
    final bloc = context.read<PolicyEditorBloc>();
    final draft = await showDialog<PolicyDraft>(
      context: context,
      builder: (_) => _PolicyBuilderDialog(org: org, catalog: bloc.state.catalog),
    );
    if (draft != null) bloc.add(PolicyCreateRequested(draft));
  }
}

class _PolicyCard extends StatelessWidget {
  const _PolicyCard({required this.policy, required this.org});

  final Policy policy;
  final String org;

  @override
  Widget build(BuildContext context) {
    final deny = policy.effect == 'DENY';
    final dimmed = !policy.active;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Opacity(
        opacity: dimmed ? 0.55 : 1,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(deny ? Icons.block : Icons.check_circle,
                  color: deny ? Colors.red : Colors.green),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Text(policy.name,
                          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                      const SizedBox(width: 8),
                      Chip(
                        label: Text('${policy.effect} · p${policy.priority}'),
                        visualDensity: VisualDensity.compact,
                        labelStyle: const TextStyle(fontSize: 11),
                      ),
                      if (policy.hasCondition) ...[
                        const SizedBox(width: 6),
                        const Chip(
                          avatar: Icon(Icons.rule, size: 13, color: AppBrand.violet),
                          label: Text('conditional'),
                          visualDensity: VisualDensity.compact,
                          labelStyle: TextStyle(fontSize: 11),
                        ),
                      ],
                    ]),
                    if (policy.targetActions.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: policy.targetActions
                            .map((a) => Chip(
                                  label: Text(a, style: const TextStyle(fontSize: 11)),
                                  visualDensity: VisualDensity.compact,
                                ))
                            .toList(),
                      ),
                    ],
                    if (deny && (policy.userReason ?? '').isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Text('User sees: "${policy.userReason}"',
                          style: const TextStyle(fontSize: 12, color: Colors.black54)),
                    ],
                  ],
                ),
              ),
              Tooltip(
                message: policy.active ? 'Active — click to disable' : 'Disabled — click to enable',
                child: Switch(
                  value: policy.active,
                  onChanged: (v) => context
                      .read<PolicyEditorBloc>()
                      .add(PolicyActiveToggled(org, policy.id, v)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Starter templates: prefill the builder so common policies are two edits away.
class _PolicyTemplate {
  const _PolicyTemplate(this.label, {
    required this.effect,
    this.priority = 100,
    this.rows = const [],
    this.userReason = '',
    this.auditReason = '',
  });

  final String label;
  final String effect;
  final int priority;
  final List<ConditionRow> rows;
  final String userReason;
  final String auditReason;

  static const templates = [
    _PolicyTemplate('Start from scratch', effect: 'DENY'),
    _PolicyTemplate('Freeze actions (unconditional DENY)',
        effect: 'DENY',
        userReason: 'This operation is temporarily unavailable.',
        auditReason: 'ops freeze — see incident ticket'),
    _PolicyTemplate('Deny over an amount limit',
        effect: 'DENY',
        rows: [ConditionRow(scope: 'resource', attribute: 'amount', op: '>', value: '500')],
        userReason: 'This amount is above your approval limit.',
        auditReason: 'resource.amount exceeded the configured cap'),
    _PolicyTemplate('Permit for a department',
        effect: 'PERMIT',
        priority: 50,
        rows: [ConditionRow(scope: 'subject', attribute: 'department', op: '==', value: 'finance')]),
  ];
}

class _PolicyBuilderDialog extends StatefulWidget {
  const _PolicyBuilderDialog({required this.org, required this.catalog});

  final String org;
  final List<String> catalog;

  @override
  State<_PolicyBuilderDialog> createState() => _PolicyBuilderDialogState();
}

class _PolicyBuilderDialogState extends State<_PolicyBuilderDialog> {
  final _name = TextEditingController();
  final _priority = TextEditingController(text: '100');
  final _userReason = TextEditingController();
  final _auditReason = TextEditingController();
  final _advancedJson = TextEditingController();

  _PolicyTemplate _template = _PolicyTemplate.templates.first;
  String _effect = 'DENY';
  Set<String> _actions = {};
  List<ConditionRow> _rows = [];
  bool _allMustMatch = true;
  bool _advanced = false;

  @override
  void dispose() {
    for (final c in [_name, _priority, _userReason, _auditReason, _advancedJson]) {
      c.dispose();
    }
    super.dispose();
  }

  void _applyTemplate(_PolicyTemplate t) {
    setState(() {
      _template = t;
      _effect = t.effect;
      _priority.text = '${t.priority}';
      _rows = [...t.rows];
      _userReason.text = t.userReason;
      _auditReason.text = t.auditReason;
    });
  }

  String? get _conditionJson {
    if (_advanced) {
      final raw = _advancedJson.text.trim();
      return raw.isEmpty ? null : raw;
    }
    return buildConditionJson(_rows, allMustMatch: _allMustMatch);
  }

  bool get _valid {
    if (_name.text.trim().isEmpty || _actions.isEmpty) return false;
    if (_effect == 'DENY' &&
        (_userReason.text.trim().isEmpty ||
            _auditReason.text.trim().isEmpty ||
            _userReason.text.trim() == _auditReason.text.trim())) {
      return false;
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('New policy'),
      content: SizedBox(
        width: 620,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DropdownButtonFormField<_PolicyTemplate>(
                initialValue: _template,
                decoration: const InputDecoration(labelText: 'Template'),
                items: _PolicyTemplate.templates
                    .map((t) => DropdownMenuItem(value: t, child: Text(t.label)))
                    .toList(),
                onChanged: (t) => t == null ? null : _applyTemplate(t),
              ),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  flex: 3,
                  child: TextField(
                    controller: _name,
                    autofocus: true,
                    onChanged: (_) => setState(() {}),
                    decoration: const InputDecoration(labelText: 'Name'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  flex: 2,
                  child: DropdownButtonFormField<String>(
                    initialValue: _effect,
                    decoration: const InputDecoration(labelText: 'Effect'),
                    items: const [
                      DropdownMenuItem(value: 'PERMIT', child: Text('PERMIT')),
                      DropdownMenuItem(value: 'DENY', child: Text('DENY')),
                    ],
                    onChanged: (v) => setState(() => _effect = v ?? 'DENY'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _priority,
                    decoration: const InputDecoration(labelText: 'Priority'),
                    keyboardType: TextInputType.number,
                  ),
                ),
              ]),
              const SizedBox(height: 16),
              const Text('Applies to actions', style: TextStyle(fontWeight: FontWeight.w600)),
              PermissionPicker(
                catalog: widget.catalog,
                selected: _actions,
                maxHeight: 200,
                onChanged: (next) => setState(() => _actions = next),
              ),
              const SizedBox(height: 16),
              Row(children: [
                const Text('Condition', style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(width: 8),
                const Text('(optional — applies only when true)',
                    style: TextStyle(fontSize: 12, color: Colors.black54)),
                const Spacer(),
                const Text('advanced JSON', style: TextStyle(fontSize: 12)),
                Switch(
                  value: _advanced,
                  onChanged: (v) => setState(() {
                    _advanced = v;
                    if (v) {
                      _advancedJson.text =
                          buildConditionJson(_rows, allMustMatch: _allMustMatch) ?? '';
                    }
                  }),
                ),
              ]),
              if (_advanced)
                TextField(
                  controller: _advancedJson,
                  maxLines: 3,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                  decoration: const InputDecoration(
                    hintText: '{">":[{"var":"resource.amount"},{"var":"subject.refundLimit"}]}',
                  ),
                )
              else ...[
                for (var i = 0; i < _rows.length; i++) _conditionRow(i),
                Row(children: [
                  TextButton.icon(
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('Add condition'),
                    onPressed: () => setState(() => _rows = [..._rows, const ConditionRow()]),
                  ),
                  const Spacer(),
                  if (_rows.length > 1)
                    SegmentedButton<bool>(
                      style: const ButtonStyle(visualDensity: VisualDensity.compact),
                      segments: const [
                        ButtonSegment(value: true, label: Text('ALL must match')),
                        ButtonSegment(value: false, label: Text('ANY may match')),
                      ],
                      selected: {_allMustMatch},
                      onSelectionChanged: (s) => setState(() => _allMustMatch = s.first),
                    ),
                ]),
              ],
              if (_effect == 'DENY') ...[
                const Divider(height: 28),
                const Text('A DENY carries two distinct reasons',
                    style: TextStyle(fontSize: 12, color: Colors.black54)),
                const SizedBox(height: 8),
                TextField(
                  controller: _userReason,
                  onChanged: (_) => setState(() {}),
                  decoration: const InputDecoration(
                      labelText: 'User-safe reason (what the app shows)'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _auditReason,
                  onChanged: (_) => setState(() {}),
                  decoration:
                      const InputDecoration(labelText: 'Audit reason (operators only)'),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: !_valid
              ? null
              : () => Navigator.pop(
                    context,
                    PolicyDraft(
                      org: widget.org,
                      name: _name.text.trim(),
                      effect: _effect,
                      targetJson: jsonEncode({'actions': _actions.toList()..sort()}),
                      conditionJson: _conditionJson,
                      priority: int.tryParse(_priority.text.trim()) ?? 0,
                      userReason: _effect == 'DENY' ? _userReason.text.trim() : null,
                      auditReason: _effect == 'DENY' ? _auditReason.text.trim() : null,
                    ),
                  ),
          child: const Text('Create'),
        ),
      ],
    );
  }

  Widget _conditionRow(int i) {
    final row = _rows[i];
    void update(ConditionRow next) => setState(() {
          _rows = [..._rows]..[i] = next;
        });
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(children: [
        SizedBox(
          width: 130,
          child: DropdownButtonFormField<String>(
            initialValue: row.scope,
            items: ConditionRow.scopes
                .map((s) => DropdownMenuItem(value: s, child: Text(s)))
                .toList(),
            onChanged: (v) => update(row.copyWith(scope: v)),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: TextFormField(
            initialValue: row.attribute,
            decoration: const InputDecoration(hintText: 'attribute (amount, department…)'),
            onChanged: (v) => update(row.copyWith(attribute: v)),
          ),
        ),
        const SizedBox(width: 8),
        SizedBox(
          width: 84,
          child: DropdownButtonFormField<String>(
            initialValue: row.op,
            items: ConditionRow.operators
                .map((o) => DropdownMenuItem(value: o, child: Text(o)))
                .toList(),
            onChanged: (v) => update(row.copyWith(op: v)),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: TextFormField(
            initialValue: row.value,
            decoration: InputDecoration(
                hintText: row.op == 'in' ? 'a, b, c' : 'value'),
            onChanged: (v) => update(row.copyWith(value: v)),
          ),
        ),
        IconButton(
          icon: const Icon(Icons.close, size: 18),
          onPressed: () => setState(() => _rows = [..._rows]..removeAt(i)),
        ),
      ]),
    );
  }
}
