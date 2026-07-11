import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/domain/money.dart';
import '../../../../core/widgets/widgets.dart';
import '../../../orders/domain/entities/order_entities.dart';
import '../../domain/entities/rma_entities.dart';
import '../bloc/rmas_bloc.dart';

/// Returns desk: RMA list with a status filter + create-from-order.
class ReturnsPage extends StatelessWidget {
  const ReturnsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<RmasBloc, RmasState>(
      listenWhen: (prev, next) => next.status == RmasStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        return Scaffold(
          floatingActionButton: FloatingActionButton.extended(
            onPressed: () => _promptCreate(context),
            icon: const Icon(Icons.assignment_return),
            label: const Text('New RMA'),
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
                        ...Rma.statuses
                            .map((s) => DropdownMenuItem<String?>(value: s, child: Text(s))),
                      ],
                      onChanged: (v) =>
                          context.read<RmasBloc>().add(RmasRequested(statusFilter: v)),
                    ),
                    const SizedBox(width: 16),
                    OutlinedButton.icon(
                      onPressed: () => context
                          .read<RmasBloc>()
                          .add(RmasRequested(statusFilter: state.statusFilter)),
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

  Widget _body(BuildContext context, RmasState state) {
    switch (state.status) {
      case RmasStatus.initial:
      case RmasStatus.loading:
        return const LoadingView();
      case RmasStatus.failure:
        return ErrorView(
          state.error ?? 'Failed to load RMAs',
          onRetry: () =>
              context.read<RmasBloc>().add(RmasRequested(statusFilter: state.statusFilter)),
        );
      case RmasStatus.loaded:
        if (state.rmas.isEmpty) {
          return const EmptyView('No returns match this filter.');
        }
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: DataTable(
            showCheckboxColumn: false,
            columns: const [
              DataColumn(label: Text('RMA')),
              DataColumn(label: Text('Order')),
              DataColumn(label: Text('Status')),
              DataColumn(label: Text('Lines')),
              DataColumn(label: Text('Created')),
            ],
            rows: state.rmas
                .map((r) => DataRow(
                      onSelectChanged: (_) => context.go('/returns/${r.id}'),
                      cells: [
                        DataCell(
                            Text(r.id, style: const TextStyle(fontFamily: 'monospace'))),
                        DataCell(Text(r.orderId)),
                        DataCell(StatusChip(r.status)),
                        DataCell(
                            Text(r.lines.map((l) => '${l.sku}×${l.qty}').join(', '))),
                        DataCell(Text(formatInstant(r.createdAt))),
                      ],
                    ))
                .toList(),
          ),
        );
    }
  }

  Future<void> _promptCreate(BuildContext context) async {
    final bloc = context.read<RmasBloc>();
    String? orderId;
    final reason = TextEditingController();
    final qtys = <String, TextEditingController>{};
    await showDialog<void>(
      context: context,
      builder: (ctx) => BlocProvider.value(
        value: bloc,
        child: StatefulBuilder(
          builder: (ctx, setState) => AlertDialog(
            title: const Text('New RMA'),
            content: SizedBox(
              width: 460,
              child: BlocBuilder<RmasBloc, RmasState>(
                builder: (ctx, state) {
                  final order = state.lookupOrder;
                  return Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: DropdownButtonFormField<String>(
                              initialValue: orderId,
                              isExpanded: true,
                              decoration:
                                  const InputDecoration(labelText: 'Paid order'),
                              items: state.paidOrders
                                  .map((o) => DropdownMenuItem(
                                        value: o.id,
                                        child: Text(_orderLabel(o),
                                            overflow: TextOverflow.ellipsis),
                                      ))
                                  .toList(),
                              onChanged: (v) => setState(() => orderId = v),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: FilledButton.tonal(
                              onPressed: orderId == null
                                  ? null
                                  : () => bloc.add(RmaOrderLookupRequested(orderId!)),
                              child: const Text('Load lines'),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      if (order != null && order.id == orderId) ...[
                        const Align(
                          alignment: Alignment.centerLeft,
                          child: Text('Return quantities'),
                        ),
                        ...order.lines.map((l) {
                          qtys.putIfAbsent(l.sku, TextEditingController.new);
                          return Padding(
                            padding: const EdgeInsets.only(top: 6),
                            child: Row(
                              children: [
                                Expanded(child: Text('${l.sku} (bought ${l.qty})')),
                                SizedBox(
                                  width: 90,
                                  child: TextField(
                                    controller: qtys[l.sku],
                                    keyboardType: TextInputType.number,
                                    decoration: const InputDecoration(labelText: 'Qty'),
                                  ),
                                ),
                              ],
                            ),
                          );
                        }),
                      ],
                      const SizedBox(height: 12),
                      TextField(
                        controller: reason,
                        decoration: const InputDecoration(labelText: 'Reason (optional)'),
                      ),
                    ],
                  );
                },
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
              FilledButton(
                onPressed: () {
                  final lines = qtys.entries
                      .map((e) =>
                          RmaLine(sku: e.key, qty: int.tryParse(e.value.text) ?? 0))
                      .where((l) => l.qty > 0)
                      .toList();
                  if (orderId == null || lines.isEmpty) return;
                  bloc.add(RmaCreateRequested(
                    orderId!,
                    lines,
                    reason.text.trim().isEmpty ? null : reason.text.trim(),
                  ));
                  Navigator.pop(ctx);
                },
                child: const Text('Create'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _orderLabel(Order o) {
    final shortId = o.id.length > 12 ? '${o.id.substring(0, 12)}…' : o.id;
    return '$shortId · ${o.customerRef ?? 'no customer'} · ${formatMoney(o.totals.grand)}';
  }
}
