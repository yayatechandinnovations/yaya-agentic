import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../domain/entities/decision.dart';
import '../../domain/entities/decision_query.dart';
import '../bloc/simulator_bloc.dart';

/// The policy simulator (US-45/US-61): dry-run a decision and render the outcome.
/// A DENY shows the user-safe reason prominently and the audit reason in a
/// separate operator-only panel — the two never blur together (#3).
class PolicySimulatorPage extends StatefulWidget {
  const PolicySimulatorPage({super.key});

  @override
  State<PolicySimulatorPage> createState() => _PolicySimulatorPageState();
}

class _PolicySimulatorPageState extends State<PolicySimulatorPage> {
  final _org = TextEditingController();
  final _subject = TextEditingController();
  final _action = TextEditingController();
  final _resourceType = TextEditingController();
  final _resourceId = TextEditingController();

  @override
  void dispose() {
    for (final c in [_org, _subject, _action, _resourceType, _resourceId]) {
      c.dispose();
    }
    super.dispose();
  }

  void _run(BuildContext context) {
    context.read<SimulatorBloc>().add(SimulateRequested(DecisionQuery(
          org: _org.text.trim(),
          subject: _subject.text.trim(),
          action: _action.text.trim(),
          resourceType: _resourceType.text.trim(),
          resourceId: _resourceId.text.trim(),
        )));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Policy simulator')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: ListView(
          children: [
            _field(_org, 'Organization id'),
            _field(_subject, 'Subject (user id)'),
            _field(_action, 'Action  (e.g. sales.order:refund)'),
            Row(children: [
              Expanded(child: _field(_resourceType, 'Resource type (optional)')),
              const SizedBox(width: 12),
              Expanded(child: _field(_resourceId, 'Resource id (optional)')),
            ]),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: () => _run(context),
              icon: const Icon(Icons.play_arrow),
              label: const Text('Simulate'),
            ),
            const SizedBox(height: 24),
            BlocBuilder<SimulatorBloc, SimulatorState>(
              builder: (context, state) => switch (state.status) {
                SimulatorStatus.idle => const SizedBox.shrink(),
                SimulatorStatus.running => const Center(child: CircularProgressIndicator()),
                SimulatorStatus.failure => Semantics(
                    liveRegion: true,
                    child: Text(state.error ?? 'Failed', style: const TextStyle(color: Colors.red)),
                  ),
                SimulatorStatus.result => _DecisionResult(decision: state.decision!),
              },
            ),
          ],
        ),
      ),
    );
  }

  Widget _field(TextEditingController c, String label) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: TextField(controller: c, decoration: InputDecoration(labelText: label)),
      );
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
