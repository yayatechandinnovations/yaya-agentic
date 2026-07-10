import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../../domain/entities/decision.dart';
import '../../domain/entities/decision_query.dart';
import '../bloc/simulator_bloc.dart';

/// The policy simulator (US-45/US-61): dry-run a decision and render the
/// outcome. Subject and action are picked from the org's members and the
/// declared catalog. A DENY shows the user-safe reason prominently and the
/// audit reason in a separate operator-only panel — the two never blur (#3).
class PolicySimulatorPage extends StatefulWidget {
  const PolicySimulatorPage({super.key});

  @override
  State<PolicySimulatorPage> createState() => _PolicySimulatorPageState();
}

class _PolicySimulatorPageState extends State<PolicySimulatorPage> {
  final _resourceType = TextEditingController();
  final _resourceId = TextEditingController();
  String? _subject;
  String? _action;

  @override
  void dispose() {
    _resourceType.dispose();
    _resourceId.dispose();
    super.dispose();
  }

  void _run(BuildContext context, String org) {
    if (_subject == null || _action == null) return;
    context.read<SimulatorBloc>().add(SimulateRequested(DecisionQuery(
          org: org,
          subject: _subject!,
          action: _action!,
          resourceType: _resourceType.text.trim(),
          resourceId: _resourceId.text.trim(),
        )));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Policy simulator'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) {
          setState(() {
            _subject = null;
            _action = null;
          });
          context.read<SimulatorBloc>().add(SimulatorContextRequested(orgId));
        },
        builder: (context, orgId) => BlocBuilder<SimulatorBloc, SimulatorState>(
          builder: (context, state) => ListView(
            padding: const EdgeInsets.all(24),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('Would this be allowed?',
                          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 16),
                      DropdownButtonFormField<String>(
                        initialValue: _subject,
                        decoration: const InputDecoration(labelText: 'Subject (member)'),
                        items: state.subjects
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
                        onChanged: (v) => setState(() => _subject = v),
                      ),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<String>(
                        initialValue: _action,
                        decoration: const InputDecoration(labelText: 'Action'),
                        items: state.actions
                            .map((a) => DropdownMenuItem(value: a, child: Text(a)))
                            .toList(),
                        onChanged: (v) => setState(() => _action = v),
                      ),
                      const SizedBox(height: 12),
                      Row(children: [
                        Expanded(
                          child: TextField(
                            controller: _resourceType,
                            decoration: const InputDecoration(
                                labelText: 'Resource type (optional)', hintText: 'store'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextField(
                            controller: _resourceId,
                            decoration: const InputDecoration(
                                labelText: 'Resource id (optional)', hintText: 'st_123'),
                          ),
                        ),
                      ]),
                      const SizedBox(height: 16),
                      FilledButton.icon(
                        onPressed: _subject == null || _action == null
                            ? null
                            : () => _run(context, orgId),
                        icon: const Icon(Icons.play_arrow),
                        label: const Text('Simulate'),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              switch (state.status) {
                SimulatorStatus.idle => const SizedBox.shrink(),
                SimulatorStatus.running =>
                  const Center(child: CircularProgressIndicator()),
                SimulatorStatus.failure => Semantics(
                    liveRegion: true,
                    child: Text(state.error ?? 'Failed',
                        style: const TextStyle(color: Colors.red)),
                  ),
                SimulatorStatus.result => _DecisionResult(decision: state.decision!),
              },
            ],
          ),
        ),
      ),
    );
  }
}

class _DecisionResult extends StatelessWidget {
  const _DecisionResult({required this.decision});

  final Decision decision;

  @override
  Widget build(BuildContext context) {
    if (decision.permit) {
      return const Card(
        color: Color(0xFFE6F4EA),
        child: ListTile(
          leading: Icon(Icons.check_circle, color: Colors.green),
          title: Text('PERMIT'),
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Card(
          color: Color(0xFFFCE8E6),
          child: ListTile(
            leading: Icon(Icons.block, color: Colors.red),
            title: Text('DENY'),
          ),
        ),
        const SizedBox(height: 8),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: _ReasonPanel(
                title: 'Shown to the user',
                icon: Icons.person,
                body: decision.userSafeReason ?? '—',
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _ReasonPanel(
                title: 'Audit reason (operators only)',
                icon: Icons.lock,
                body: decision.auditReason ?? '—',
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _ReasonPanel extends StatelessWidget {
  const _ReasonPanel({required this.title, required this.icon, required this.body});

  final String title;
  final IconData icon;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(icon, size: 16),
              const SizedBox(width: 6),
              Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
            ]),
            const SizedBox(height: 8),
            Text(body),
          ],
        ),
      ),
    );
  }
}
