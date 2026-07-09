import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/domain/money.dart';
import '../../../../core/widgets/widgets.dart';
import '../../../shipments/presentation/bloc/shipments_bloc.dart';
import '../../../shipments/presentation/widgets/shipments_panel.dart';
import '../../domain/entities/order_entities.dart';
import '../bloc/order_detail_bloc.dart';

/// One order: lines, totals (tax-inclusive aware), delivery snapshot, event
/// timeline, tenders + payment ledger, and the operator actions (cancel, add
/// tender, settle, refund) plus the shipments panel.
class OrderDetailPage extends StatelessWidget {
  const OrderDetailPage({super.key, required this.orderId});

  final String orderId;

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<OrderDetailBloc, OrderDetailState>(
      listenWhen: (prev, next) =>
          next.status == OrderDetailStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        final detail = state.detail;
        if (detail == null) {
          if (state.status == OrderDetailStatus.failure) {
            return ErrorView(
              state.error ?? 'Failed to load order',
              onRetry: () =>
                  context.read<OrderDetailBloc>().add(OrderDetailRequested(orderId)),
            );
          }
          return const LoadingView();
        }
        final order = detail.order;
        return Scaffold(
          appBar: AppBar(
            leading: IconButton(
              icon: const Icon(Icons.arrow_back),
              onPressed: () => context.go('/orders'),
            ),
            title: Text('Order $orderId'),
            actions: [
              StatusChip(order.status),
              const SizedBox(width: 8),
              StatusChip(order.paymentState),
              const SizedBox(width: 16),
              FilledButton.tonal(
                onPressed: () => _promptAddTender(context, order),
                child: const Text('Add tender'),
              ),
              const SizedBox(width: 8),
              FilledButton.tonal(
                onPressed: () => _promptRefund(context, order),
                child: const Text('Refund'),
              ),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: () async {
                  final bloc = context.read<OrderDetailBloc>();
                  if (await confirmAction(
                      context, 'Cancel order', 'Cancel order $orderId?')) {
                    bloc.add(const OrderCancelRequested());
                  }
                },
                child: const Text('Cancel order'),
              ),
              const SizedBox(width: 16),
            ],
          ),
          body: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              KeyValueRow('Customer', order.customerRef ?? '—'),
              KeyValueRow('Source', order.source ?? '—'),
              KeyValueRow('Fulfillment', order.fulfillmentState ?? '—'),
              KeyValueRow('Created', formatInstant(order.createdAt)),
              const Divider(height: 32),
              const SectionHeader('Lines'),
              DataTable(
                columns: const [
                  DataColumn(label: Text('SKU')),
                  DataColumn(label: Text('Title')),
                  DataColumn(label: Text('Qty'), numeric: true),
                  DataColumn(label: Text('Unit price')),
                ],
                rows: order.lines
                    .map((l) => DataRow(cells: [
                          DataCell(Text(l.sku)),
                          DataCell(Text(l.title)),
                          DataCell(Text('${l.qty}')),
                          DataCell(MoneyText(l.unitPrice)),
                        ]))
                    .toList(),
              ),
              const SizedBox(height: 16),
              _totals(order),
              if (order.delivery != null) ...[
                const Divider(height: 32),
                const SectionHeader('Delivery'),
                KeyValueRow('Method', order.delivery!.label ?? order.delivery!.kind ?? '—'),
                KeyValueRow('Price', formatMoney(order.delivery!.price)),
                KeyValueRow(
                    'Promised',
                    '${formatInstant(order.delivery!.promisedFrom)} → '
                        '${formatInstant(order.delivery!.promisedTo)}'),
                KeyValueRow('Ship to', order.delivery!.shipTo ?? '—'),
              ],
              const Divider(height: 32),
              const SectionHeader('Tenders'),
              if (detail.tenders.isEmpty)
                const EmptyView('No tenders on this order.')
              else
                DataTable(
                  columns: const [
                    DataColumn(label: Text('Kind')),
                    DataColumn(label: Text('Amount')),
                    DataColumn(label: Text('Status')),
                    DataColumn(label: Text('Settled at')),
                    DataColumn(label: Text('')),
                  ],
                  rows: detail.tenders
                      .map((t) => DataRow(cells: [
                            DataCell(StatusChip(t.kind)),
                            DataCell(MoneyText(t.amount)),
                            DataCell(StatusChip(t.status)),
                            DataCell(Text(formatInstant(t.settledAt))),
                            DataCell(t.status == 'PENDING'
                                ? FilledButton.tonal(
                                    onPressed: () => context
                                        .read<OrderDetailBloc>()
                                        .add(TenderSettleRequested(t.id)),
                                    child: const Text('Settle'),
                                  )
                                : const SizedBox.shrink()),
                          ]))
                      .toList(),
                ),
              const Divider(height: 32),
              const SectionHeader('Event timeline'),
              if (detail.events.isEmpty)
                const EmptyView('No events recorded.')
              else
                ...detail.events.map((e) => ListTile(
                      dense: true,
                      leading: const Icon(Icons.circle, size: 10),
                      title: Text(
                          '${e.type}${e.fromState != null ? '  ${e.fromState} → ${e.toState}' : ''}'),
                      subtitle: Text('${e.actor ?? ''} · ${formatInstant(e.at)}'),
                    )),
              const Divider(height: 32),
              BlocProvider.value(
                value: context.read<ShipmentsBloc>(),
                child: ShipmentsPanel(orderId: orderId, orderLines: order.lines),
              ),
              const SizedBox(height: 48),
            ],
          ),
        );
      },
    );
  }

  Widget _totals(Order order) {
    final t = order.totals;
    return Align(
      alignment: Alignment.centerRight,
      child: SizedBox(
        width: 360,
        child: Column(
          children: [
            KeyValueRow('Subtotal', formatMoney(t.subtotal)),
            KeyValueRow('Discount', formatMoney(t.discount)),
            KeyValueRow(t.taxInclusive ? 'Tax (included in prices)' : 'Tax',
                formatMoney(t.tax)),
            KeyValueRow('Shipping', formatMoney(t.shipping)),
            const Divider(),
            KeyValueRow('Grand total', formatMoney(t.grand)),
          ],
        ),
      ),
    );
  }

  Future<void> _promptAddTender(BuildContext context, Order order) async {
    final bloc = context.read<OrderDetailBloc>();
    var kind = Tender.kinds.first;
    final amount = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Add tender'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: kind,
                decoration: const InputDecoration(labelText: 'Kind'),
                items: Tender.kinds
                    .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                    .toList(),
                onChanged: (v) => setState(() => kind = v ?? kind),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: amount,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: 'Amount (minor units, ${order.currency})',
                  helperText: '1999 = 19.99',
                ),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
          ],
        ),
      ),
    );
    if (ok == true) {
      bloc.add(TenderAddRequested(kind, int.tryParse(amount.text) ?? 0));
    }
  }

  Future<void> _promptRefund(BuildContext context, Order order) async {
    final bloc = context.read<OrderDetailBloc>();
    final amount = TextEditingController();
    var toStoreCredit = false;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Refund'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              TextField(
                controller: amount,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: 'Amount (minor units, ${order.currency})',
                  helperText: 'Leave empty for a FULL refund',
                ),
              ),
              const SizedBox(height: 8),
              SwitchListTile(
                title: const Text('Refund to store credit'),
                subtitle: const Text('Off = back to the original PSP tender'),
                value: toStoreCredit,
                onChanged: (v) => setState(() => toStoreCredit = v),
              ),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Refund')),
          ],
        ),
      ),
    );
    if (ok == true) {
      bloc.add(OrderRefundRequested(
        amountMinor: amount.text.trim().isEmpty ? null : int.tryParse(amount.text.trim()),
        toStoreCredit: toStoreCredit,
      ));
    }
  }
}
