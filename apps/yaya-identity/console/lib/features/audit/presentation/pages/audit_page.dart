import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../bloc/audit_bloc.dart';

/// Append-only audit viewer. A DENY row exposes both reasons (operator view);
/// the user-safe reason is what an app would surface, the audit reason is internal.
class AuditPage extends StatefulWidget {
  const AuditPage({super.key});

  @override
  State<AuditPage> createState() => _AuditPageState();
}

class _AuditPageState extends State<AuditPage> {
  final _org = TextEditingController();

  @override
  void dispose() {
    _org.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Audit log')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _org,
                    decoration: const InputDecoration(labelText: 'Organization id'),
                    onSubmitted: (v) => context.read<AuditBloc>().add(AuditRequested(v.trim())),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton.icon(
                  onPressed: () => context.read<AuditBloc>().add(AuditRequested(_org.text.trim())),
                  icon: const Icon(Icons.search),
                  label: const Text('Load'),
                ),
              ],
            ),
          ),
          Expanded(
            child: BlocBuilder<AuditBloc, AuditState>(
              builder: (context, state) => switch (state.status) {
                AuditStatus.initial => const Center(child: Text('Enter an org id and load.')),
                AuditStatus.loading => const Center(child: CircularProgressIndicator()),
                AuditStatus.failure => Center(
                    child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')),
                  ),
                AuditStatus.loaded => state.rows.isEmpty
                    ? const Center(child: Text('No audit entries.'))
                    : ListView.separated(
                        itemCount: state.rows.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (_, i) {
                          final r = state.rows[i];
                          return ListTile(
                            leading: Icon(
                              r.decision == null
                                  ? Icons.event_note
                                  : (r.isDeny ? Icons.block : Icons.check_circle),
                              color: r.decision == null
                                  ? null
                                  : (r.isDeny ? Colors.red : Colors.green),
                            ),
                            title: Text(r.action ?? r.eventType),
                            subtitle: Text([
                              if (r.subject != null) 'subject=${r.subject}',
                              if (r.resource != null) 'resource=${r.resource}',
                              if (r.isDeny && r.auditReason != null) 'audit: ${r.auditReason}',
                            ].join('   ')),
                            trailing: Text(r.at ?? '', style: const TextStyle(fontSize: 11)),
                          );
                        },
                      ),
              },
            ),
          ),
        ],
      ),
    );
  }
}
