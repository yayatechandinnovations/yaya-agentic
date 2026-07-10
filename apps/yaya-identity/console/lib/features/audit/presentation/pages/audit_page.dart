import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/org_scope_gate.dart';
import '../../../../core/widgets/org_scope_picker.dart';
import '../bloc/audit_bloc.dart';

/// Append-only audit viewer, scoped by the console-wide org picker. A DENY row
/// exposes both reasons (operator view); the user-safe reason is what an app
/// would surface, the audit reason is internal.
class AuditPage extends StatelessWidget {
  const AuditPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Audit log'), actions: const [OrgScopePicker()]),
      body: OrgScopeGate(
        onOrg: (context, orgId) => context.read<AuditBloc>().add(AuditRequested(orgId)),
        builder: (context, orgId) => BlocBuilder<AuditBloc, AuditState>(
          builder: (context, state) => switch (state.status) {
            AuditStatus.initial ||
            AuditStatus.loading =>
              const Center(child: CircularProgressIndicator()),
            AuditStatus.failure => Center(
                child: Semantics(liveRegion: true, child: Text(state.error ?? 'Failed')),
              ),
            AuditStatus.loaded => state.rows.isEmpty
                ? const Center(child: Text('No audit entries.'))
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
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
    );
  }
}
