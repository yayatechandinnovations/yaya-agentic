import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/rma_entities.dart';
import '../bloc/rmas_bloc.dart';

/// One RMA: authorize / reject / cancel, return-shipment info, receive
/// (per-line dispositions + refund routing).
class RmaDetailPage extends StatelessWidget {
  const RmaDetailPage({super.key, required this.rmaId});

  final String rmaId;

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<RmasBloc, RmasState>(
      listenWhen: (prev, next) => next.status == RmasStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        final rma = state.selected;
        if (rma == null || rma.id != rmaId) {
          return const LoadingView();
        }
        return Scaffold(
          appBar: AppBar(
            leading: IconButton(
              icon: const Icon(Icons.arrow_back),
              onPressed: () => context.go('/returns'),
            ),
            title: Text('RMA $rmaId'),
            actions: [
              StatusChip(rma.status),
              const SizedBox(width: 16),
              if (rma.status == 'REQUESTED') ...[
                FilledButton.tonal(
                  onPressed: () =>
                      context.read<RmasBloc>().add(RmaAuthorizeRequested(rma.id)),
                  child: const Text('Authorize'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () => _promptReject(context, rma),
                  child: const Text('Reject'),
                ),
              ],
              if (rma.status == 'AUTHORIZED') ...[
                const SizedBox(width: 8),
                FilledButton.tonal(
                  onPressed: () => _promptReceive(context, rma),
                  child: const Text('Receive'),
                ),
              ],
              if (rma.status == 'REQUESTED' || rma.status == 'AUTHORIZED') ...[
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () async {
                    final bloc = context.read<RmasBloc>();
                    if (await confirmAction(context, 'Cancel RMA', 'Cancel RMA ${rma.id}?')) {
                      bloc.add(RmaCancelRequested(rma.id));
                    }
                  },
                  child: const Text('Cancel'),
                ),
              ],
              const SizedBox(width: 16),
            ],
          ),
          body: ListView(
            padding: const EdgeInsets.all(24),
            children: [
              KeyValueRow('Order', rma.orderId),
              KeyValueRow('Reason', rma.reason ?? '—'),
              KeyValueRow('Created', formatInstant(rma.createdAt)),
              KeyValueRow('Received', formatInstant(rma.receivedAt)),
              KeyValueRow('Refund ref', rma.refundRef ?? '—'),
              const Divider(height: 32),
              const SectionHeader('Authorized lines'),
              DataTable(
                columns: const [
                  DataColumn(label: Text('SKU')),
                  DataColumn(label: Text('Qty'), numeric: true),
                ],
                rows: rma.lines
                    .map((l) => DataRow(cells: [
                          DataCell(Text(l.sku)),
                          DataCell(Text('${l.qty}')),
                        ]))
                    .toList(),
              ),
              const Divider(height: 32),
              SectionHeader('Return shipment', actions: [
                OutlinedButton.icon(
                  onPressed: () => _promptReturnShipment(context, rma),
                  icon: const Icon(Icons.edit),
                  label: const Text('Edit'),
                ),
              ]),
              KeyValueRow('Carrier', rma.returnShipment?.carrier ?? '—'),
              KeyValueRow('Tracking', rma.returnShipment?.trackingNumber ?? '—'),
              KeyValueRow('Instructions', rma.returnShipment?.instructions ?? '—'),
              if (rma.dispositions.isNotEmpty) ...[
                const Divider(height: 32),
                const SectionHeader('Dispositions'),
                DataTable(
                  columns: const [
                    DataColumn(label: Text('SKU')),
                    DataColumn(label: Text('Qty'), numeric: true),
                    DataColumn(label: Text('Disposition')),
                  ],
                  rows: rma.dispositions
                      .map((d) => DataRow(cells: [
                            DataCell(Text(d.sku)),
                            DataCell(Text('${d.qty}')),
                            DataCell(StatusChip(d.kind)),
                          ]))
                      .toList(),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  Future<void> _promptReject(BuildContext context, Rma rma) async {
    final bloc = context.read<RmasBloc>();
    final reason = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Reject RMA'),
        content: TextField(
          controller: reason,
          decoration: const InputDecoration(labelText: 'Reason'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Reject')),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(RmaRejectRequested(
          rma.id, reason.text.trim().isEmpty ? null : reason.text.trim()));
    }
  }

  Future<void> _promptReturnShipment(BuildContext context, Rma rma) async {
    final bloc = context.read<RmasBloc>();
    final carrier = TextEditingController(text: rma.returnShipment?.carrier ?? '');
    final tracking = TextEditingController(text: rma.returnShipment?.trackingNumber ?? '');
    final instructions =
        TextEditingController(text: rma.returnShipment?.instructions ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Return shipment info'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: carrier,
              decoration: const InputDecoration(labelText: 'Carrier'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: tracking,
              decoration: const InputDecoration(labelText: 'Tracking number'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: instructions,
              maxLines: 2,
              decoration: const InputDecoration(labelText: 'Instructions'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Save')),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(RmaReturnShipmentSaved(
        rma.id,
        ReturnShipmentInfo(
          carrier: carrier.text.trim().isEmpty ? null : carrier.text.trim(),
          trackingNumber: tracking.text.trim().isEmpty ? null : tracking.text.trim(),
          instructions:
              instructions.text.trim().isEmpty ? null : instructions.text.trim(),
        ),
      ));
    }
  }

  Future<void> _promptReceive(BuildContext context, Rma rma) async {
    final bloc = context.read<RmasBloc>();
    final currency = sl<StoreContext>().storeCurrency ?? 'USD';
    final amount = TextEditingController();
    var refundTo = 'store_credit';
    final qtys = {
      for (final line in rma.lines) line.sku: TextEditingController(text: '${line.qty}'),
    };
    final kinds = {for (final line in rma.lines) line.sku: RmaDisposition.kinds.first};
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Receive return'),
          content: SizedBox(
            width: 480,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Dispositions — only RESTOCK re-enters stock'),
                const SizedBox(height: 8),
                ...rma.lines.map((l) => Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Row(
                        children: [
                          Expanded(child: Text(l.sku)),
                          SizedBox(
                            width: 80,
                            child: TextField(
                              controller: qtys[l.sku],
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(labelText: 'Qty'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          DropdownButton<String>(
                            value: kinds[l.sku],
                            items: RmaDisposition.kinds
                                .map((k) => DropdownMenuItem(value: k, child: Text(k)))
                                .toList(),
                            onChanged: (v) => setState(
                                () => kinds[l.sku] = v ?? kinds[l.sku]!),
                          ),
                        ],
                      ),
                    )),
                const SizedBox(height: 8),
                TextField(
                  controller: amount,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                    labelText: 'Refund amount (minor units, $currency)',
                    helperText: 'Empty = server default',
                  ),
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  initialValue: refundTo,
                  decoration: const InputDecoration(labelText: 'Refund to'),
                  items: const [
                    DropdownMenuItem(value: 'store_credit', child: Text('Store credit')),
                    DropdownMenuItem(
                        value: 'original_tender', child: Text('Original tender')),
                  ],
                  onChanged: (v) => setState(() => refundTo = v ?? refundTo),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Receive')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final dispositions = rma.lines
        .map((l) => RmaDisposition(
              sku: l.sku,
              qty: int.tryParse(qtys[l.sku]!.text) ?? 0,
              kind: kinds[l.sku]!,
            ))
        .where((d) => d.qty > 0)
        .toList();
    bloc.add(RmaReceiveRequested(
      rma.id,
      dispositions,
      refundTo,
      amount.text.trim().isEmpty ? null : int.tryParse(amount.text.trim()),
    ));
  }
}
