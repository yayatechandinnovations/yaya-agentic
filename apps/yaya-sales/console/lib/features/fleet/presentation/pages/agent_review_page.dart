import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/fleet_entities.dart';
import '../bloc/fleet_bloc.dart';

/// KYC review for one agent: identity, vehicle, documents (decrypt-on-demand),
/// approve (courier token shown once) / reject / suspend.
class AgentReviewPage extends StatelessWidget {
  const AgentReviewPage({super.key, required this.agentId});

  final String agentId;

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<FleetBloc, FleetState>(
      listener: (context, state) {
        if (state.status == FleetStatus.failure && state.error != null) {
          showErrorSnack(context, state.error!);
        }
        if (state.mintedCredential != null) {
          showSecretDialog(
            context,
            'Courier credential',
            state.mintedCredential!.token,
            note: 'Hand this operational token to the courier — it is never shown again.',
          );
        }
      },
      builder: (context, state) {
        final agent = state.selected;
        if (agent == null || agent.id != agentId) return const LoadingView();
        return Scaffold(
          appBar: AppBar(
            leading: IconButton(
              icon: const Icon(Icons.arrow_back),
              onPressed: () => context.go('/fleet'),
            ),
            title: Text('Agent — ${agent.legalName}'),
            actions: [
              StatusChip(agent.status),
              const SizedBox(width: 16),
              // Approval requires verified phone + reviewed documents; the
              // server enforces the gate — the console just asks.
              if (agent.status != 'APPROVED')
                FilledButton.tonal(
                  onPressed: () async {
                    final bloc = context.read<FleetBloc>();
                    if (await confirmAction(context, 'Approve courier',
                        'Approve ${agent.legalName}? This mints their operational token.')) {
                      bloc.add(AgentApproveRequested(agent.id));
                    }
                  },
                  child: const Text('Approve'),
                ),
              const SizedBox(width: 8),
              if (agent.status != 'REJECTED')
                OutlinedButton(
                  onPressed: () => _promptDecision(
                      context, 'Reject', (reason) => AgentRejectRequested(agent.id, reason)),
                  child: const Text('Reject'),
                ),
              const SizedBox(width: 8),
              if (agent.status == 'APPROVED')
                OutlinedButton(
                  onPressed: () => _promptDecision(context, 'Suspend',
                      (reason) => AgentSuspendRequested(agent.id, reason)),
                  child: const Text('Suspend'),
                ),
              const SizedBox(width: 16),
            ],
          ),
          body: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              const SectionHeader('Identity'),
              KeyValueRow('Legal name', agent.legalName),
              KeyValueRow('Phone',
                  '${agent.phone ?? '—'}${agent.phoneVerified ? ' (verified)' : ' (NOT verified)'}'),
              KeyValueRow('Address', agent.legalAddress ?? '—'),
              KeyValueRow('ID document',
                  '${agent.idDocType ?? '—'} ${agent.idDocNumber ?? ''}'),
              KeyValueRow('Registered', formatInstant(agent.registeredAt)),
              if (agent.reviewedBy != null) KeyValueRow('Reviewed by', agent.reviewedBy!),
              if (agent.rejectReason != null)
                KeyValueRow('Reject reason', agent.rejectReason!),
              const Divider(height: 32),
              const SectionHeader('Vehicle'),
              KeyValueRow('Vehicle', agent.vehicle?.display ?? '—'),
              const Divider(height: 32),
              const SectionHeader('KYC documents'),
              if (agent.documents.isEmpty)
                const EmptyView('No documents submitted yet.')
              else
                ...agent.documents.map((d) => ListTile(
                      dense: true,
                      leading: const Icon(Icons.description),
                      title: Text(d.kind),
                      subtitle: Text(
                          '${d.status ?? ''} · uploaded ${formatInstant(d.uploadedAt)}'),
                      trailing: FilledButton.tonal(
                        onPressed: () => _viewDocument(context, agent, d),
                        child: const Text('View (decrypt)'),
                      ),
                    )),
            ],
          ),
        );
      },
    );
  }

  Future<void> _promptDecision(
      BuildContext context, String verb, FleetEvent Function(String?) eventOf) async {
    final bloc = context.read<FleetBloc>();
    final reason = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('$verb agent'),
        content: TextField(
          controller: reason,
          decoration: const InputDecoration(labelText: 'Reason'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(verb)),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(eventOf(reason.text.trim().isEmpty ? null : reason.text.trim()));
    }
  }

  void _viewDocument(BuildContext context, DeliveryAgent agent, AgentDocument document) {
    final bloc = context.read<FleetBloc>();
    bloc.add(DocumentContentRequested(agent.id, document.id));
    showDialog<void>(
      context: context,
      builder: (ctx) => BlocProvider.value(
        value: bloc,
        child: AlertDialog(
          title: Text('${document.kind} — decrypted view'),
          content: SizedBox(
            width: 520,
            height: 420,
            child: BlocBuilder<FleetBloc, FleetState>(
              builder: (ctx, state) {
                if (state.documentId != document.id || state.documentContent == null) {
                  return const LoadingView();
                }
                return _DecodedDocument(base64Content: state.documentContent!);
              },
            ),
          ),
          actions: [
            FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
          ],
        ),
      ),
    );
  }
}

class _DecodedDocument extends StatelessWidget {
  const _DecodedDocument({required this.base64Content});

  final String base64Content;

  @override
  Widget build(BuildContext context) {
    // KYC scans are images in practice; fall back to the raw base64 when the
    // bytes don't decode as one.
    try {
      final bytes = Uri.parse('data:application/octet-stream;base64,$base64Content')
          .data!
          .contentAsBytes();
      return InteractiveViewer(
        child: Image.memory(
          bytes,
          errorBuilder: (_, __, ___) => SingleChildScrollView(
            child: SelectableText(base64Content,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
          ),
        ),
      );
    } on FormatException {
      return SingleChildScrollView(
        child: SelectableText(base64Content,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
      );
    }
  }
}
