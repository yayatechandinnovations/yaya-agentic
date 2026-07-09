import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/fleet_entities.dart';
import '../bloc/fleet_bloc.dart';

/// Fleet desk: mint invites (token shown once), agents list with status filter.
class FleetPage extends StatelessWidget {
  const FleetPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<FleetBloc, FleetState>(
      listener: (context, state) {
        if (state.status == FleetStatus.failure && state.error != null) {
          showErrorSnack(context, state.error!);
        }
        if (state.mintedInvite != null) {
          showSecretDialog(
            context,
            'Courier invite',
            state.mintedInvite!.token,
            note:
                'Share this single-use invite with the courier. Expires ${formatInstant(state.mintedInvite!.expiresAt)}.',
          );
        }
      },
      builder: (context, state) {
        return Scaffold(
          floatingActionButton: FloatingActionButton.extended(
            onPressed: () => _promptInvite(context),
            icon: const Icon(Icons.person_add),
            label: const Text('Mint invite'),
          ),
          body: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
                child: Row(
                  children: [
                    DropdownButton<String?>(
                      value: state.statusFilter,
                      hint: const Text('All statuses'),
                      items: [
                        const DropdownMenuItem<String?>(child: Text('All statuses')),
                        ...DeliveryAgent.statuses
                            .map((s) => DropdownMenuItem<String?>(value: s, child: Text(s))),
                      ],
                      onChanged: (v) =>
                          context.read<FleetBloc>().add(AgentsRequested(statusFilter: v)),
                    ),
                    const SizedBox(width: 16),
                    OutlinedButton.icon(
                      onPressed: () => context
                          .read<FleetBloc>()
                          .add(AgentsRequested(statusFilter: state.statusFilter)),
                      icon: const Icon(Icons.refresh),
                      label: const Text('Refresh'),
                    ),
                  ],
                ),
              ),
              Expanded(child: _body(context, state)),
            ],
          ),
        );
      },
    );
  }

  Widget _body(BuildContext context, FleetState state) {
    switch (state.status) {
      case FleetStatus.initial:
      case FleetStatus.loading:
        return const LoadingView();
      case FleetStatus.failure:
        return ErrorView(
          state.error ?? 'Failed to load agents',
          onRetry: () =>
              context.read<FleetBloc>().add(AgentsRequested(statusFilter: state.statusFilter)),
        );
      case FleetStatus.loaded:
        if (state.agents.isEmpty) {
          return const EmptyView('No delivery agents — mint an invite to onboard one.');
        }
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: DataTable(
            showCheckboxColumn: false,
            columns: const [
              DataColumn(label: Text('Name')),
              DataColumn(label: Text('Phone')),
              DataColumn(label: Text('Vehicle')),
              DataColumn(label: Text('Status')),
              DataColumn(label: Text('Registered')),
            ],
            rows: state.agents
                .map((a) => DataRow(
                      onSelectChanged: (_) => context.go('/fleet/${a.id}'),
                      cells: [
                        DataCell(Text(a.legalName)),
                        DataCell(Text(
                            '${a.phone ?? '—'}${a.phoneVerified ? ' ✓' : ''}')),
                        DataCell(Text(a.vehicle?.display ?? '—')),
                        DataCell(StatusChip(a.status)),
                        DataCell(Text(formatInstant(a.registeredAt))),
                      ],
                    ))
                .toList(),
          ),
        );
    }
  }

  Future<void> _promptInvite(BuildContext context) async {
    final bloc = context.read<FleetBloc>();
    final ttl = TextEditingController(text: '72');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Mint courier invite'),
        content: TextField(
          controller: ttl,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'TTL (hours)',
            helperText: 'Single-use; token is shown exactly once',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Mint')),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(InviteMintRequested(ttlHours: int.tryParse(ttl.text)));
    }
  }
}
