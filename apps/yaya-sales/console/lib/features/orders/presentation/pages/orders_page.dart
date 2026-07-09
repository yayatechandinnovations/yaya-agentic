import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/order_entities.dart';
import '../bloc/orders_bloc.dart';

class OrdersPage extends StatelessWidget {
  const OrdersPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<OrdersBloc, OrdersState>(
      listenWhen: (prev, next) => next.status == OrdersStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        return Column(
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
                      ...Order.statuses
                          .map((s) => DropdownMenuItem<String?>(value: s, child: Text(s))),
                    ],
                    onChanged: (v) =>
                        context.read<OrdersBloc>().add(OrdersRequested(statusFilter: v)),
                  ),
                  const SizedBox(width: 16),
                  OutlinedButton.icon(
                    onPressed: () => context
                        .read<OrdersBloc>()
                        .add(OrdersRequested(statusFilter: state.statusFilter)),
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh'),
                  ),
                ],
              ),
            ),
            Expanded(child: _body(context, state)),
          ],
        );
      },
    );
  }

  Widget _body(BuildContext context, OrdersState state) {
    switch (state.status) {
      case OrdersStatus.initial:
      case OrdersStatus.loading:
        return const LoadingView();
      case OrdersStatus.failure:
        return ErrorView(
          state.error ?? 'Failed to load orders',
          onRetry: () => context
              .read<OrdersBloc>()
              .add(OrdersRequested(statusFilter: state.statusFilter)),
        );
      case OrdersStatus.loaded:
        if (state.orders.isEmpty) {
          return const EmptyView('No orders match this filter.');
        }
        return SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: DataTable(
            showCheckboxColumn: false,
            columns: const [
              DataColumn(label: Text('Order')),
              DataColumn(label: Text('Created')),
              DataColumn(label: Text('Customer')),
              DataColumn(label: Text('Status')),
              DataColumn(label: Text('Payment')),
              DataColumn(label: Text('Total')),
            ],
            rows: state.orders
                .map((o) => DataRow(
                      onSelectChanged: (_) => context.go('/orders/${o.id}'),
                      cells: [
                        DataCell(Text(o.id, style: const TextStyle(fontFamily: 'monospace'))),
                        DataCell(Text(formatInstant(o.createdAt))),
                        DataCell(Text(o.customerRef ?? '—')),
                        DataCell(StatusChip(o.status)),
                        DataCell(StatusChip(o.paymentState)),
                        DataCell(MoneyText(o.totals.grand)),
                      ],
                    ))
                .toList(),
          ),
        );
    }
  }
}
