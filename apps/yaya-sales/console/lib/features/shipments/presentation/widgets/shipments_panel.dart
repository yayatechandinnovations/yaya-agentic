import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/widgets.dart';
import '../../../orders/domain/entities/order_entities.dart';
import '../../domain/entities/shipment_entities.dart';
import '../bloc/shipments_bloc.dart';

/// The shipments section embedded in the order detail page: create shipment
/// (line/location picker), pick → pack (parcel) → dispatch (tracking), manual
/// delivery-status advance with proof, courier assignment, tracking timeline.
class ShipmentsPanel extends StatelessWidget {
  const ShipmentsPanel({super.key, required this.orderId, required this.orderLines});

  final String orderId;
  final List<OrderLine> orderLines;

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<ShipmentsBloc, ShipmentsState>(
      listener: (context, state) {
        if (state.status == ShipmentsStatus.failure && state.error != null) {
          showErrorSnack(context, state.error!);
        }
        if (state.lastAssignment != null) {
          showInfoSnack(context,
              'Assigned to agent ${state.lastAssignment!.agentId} (${state.lastAssignment!.status})');
        }
      },
      builder: (context, state) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SectionHeader('Shipments', actions: [
              FilledButton.tonalIcon(
                onPressed: () => _promptCreate(context),
                icon: const Icon(Icons.add),
                label: const Text('Create shipment'),
              ),
            ]),
            if (state.status == ShipmentsStatus.loading)
              const Padding(padding: EdgeInsets.all(16), child: LoadingView())
            else if (state.shipments.isEmpty)
              const EmptyView('No shipments yet.')
            else
              ...state.shipments.map((s) => _shipmentCard(context, s)),
          ],
        );
      },
    );
  }

  Widget _shipmentCard(BuildContext context, Shipment shipment) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                SelectableText(shipment.id,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                const SizedBox(width: 12),
                StatusChip(shipment.status),
                if (shipment.deliveryStatus != null) ...[
                  const SizedBox(width: 8),
                  StatusChip(shipment.deliveryStatus!),
                ],
                const Spacer(),
                if (shipment.status == 'DRAFT')
                  FilledButton.tonal(
                    onPressed: () => context
                        .read<ShipmentsBloc>()
                        .add(ShipmentPickRequested(shipment.id)),
                    child: const Text('Pick'),
                  ),
                if (shipment.status == 'PICKING') ...[
                  const SizedBox(width: 8),
                  FilledButton.tonal(
                    onPressed: () => _promptPack(context, shipment),
                    child: const Text('Pack'),
                  ),
                ],
                if (shipment.status == 'PACKED') ...[
                  const SizedBox(width: 8),
                  FilledButton.tonal(
                    onPressed: () => _promptDispatch(context, shipment),
                    child: const Text('Dispatch'),
                  ),
                ],
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () => _promptAdvance(context, shipment),
                  child: const Text('Advance delivery'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () => _promptAssign(context, shipment),
                  child: const Text('Assign courier'),
                ),
                const SizedBox(width: 8),
                IconButton(
                  tooltip: 'Tracking timeline',
                  icon: const Icon(Icons.timeline),
                  onPressed: () => _showTracking(context, shipment),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text('Lines: ${shipment.lines.map((l) => '${l.sku}×${l.qty}').join(', ')}'),
            if (shipment.trackingNumber != null)
              Text('Tracking: ${shipment.trackingNumber} ${shipment.trackingUrl ?? ''}'),
          ],
        ),
      ),
    );
  }

  Future<void> _promptCreate(BuildContext context) async {
    final bloc = context.read<ShipmentsBloc>();
    final location = TextEditingController();
    final qtys = {
      for (final line in orderLines) line.sku: TextEditingController(text: '${line.qty}'),
    };
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Create shipment'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: location,
                decoration: const InputDecoration(
                  labelText: 'Location id (optional)',
                  helperText: 'Empty = default sellable location',
                ),
              ),
              const SizedBox(height: 12),
              const Align(
                alignment: Alignment.centerLeft,
                child: Text('Line quantities (0 to exclude; all = full order)'),
              ),
              const SizedBox(height: 4),
              ...orderLines.map((l) => Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: Row(
                      children: [
                        Expanded(child: Text(l.sku)),
                        SizedBox(
                          width: 100,
                          child: TextField(
                            controller: qtys[l.sku],
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(labelText: 'Qty'),
                          ),
                        ),
                      ],
                    ),
                  )),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
        ],
      ),
    );
    if (ok != true) return;
    final lines = <ShipmentLine>[];
    var allFull = true;
    for (final line in orderLines) {
      final qty = int.tryParse(qtys[line.sku]!.text) ?? 0;
      if (qty != line.qty) allFull = false;
      if (qty > 0) lines.add(ShipmentLine(sku: line.sku, qty: qty));
    }
    bloc.add(ShipmentCreateRequested(
      // Full order → empty lines lets the server ship everything.
      lines: allFull ? const [] : lines,
      locationId: location.text.trim().isEmpty ? null : location.text.trim(),
    ));
  }

  Future<void> _promptPack(BuildContext context, Shipment shipment) async {
    final bloc = context.read<ShipmentsBloc>();
    final weight = TextEditingController();
    final length = TextEditingController();
    final width = TextEditingController();
    final height = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Pack shipment'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: weight,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Parcel weight (g)'),
            ),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(
                child: TextField(
                  controller: length,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'L (cm)'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: width,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'W (cm)'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: height,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'H (cm)'),
                ),
              ),
            ]),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Pack')),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(ShipmentPackRequested(
        shipment.id,
        parcel: Parcel(
          weightGrams: int.tryParse(weight.text),
          lengthCm: int.tryParse(length.text),
          widthCm: int.tryParse(width.text),
          heightCm: int.tryParse(height.text),
        ),
      ));
    }
  }

  Future<void> _promptDispatch(BuildContext context, Shipment shipment) async {
    final bloc = context.read<ShipmentsBloc>();
    final number = TextEditingController();
    final url = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Dispatch shipment'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: number,
              decoration: const InputDecoration(
                labelText: 'Tracking number (manual carrier)',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: url,
              decoration: const InputDecoration(labelText: 'Tracking URL (optional)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true), child: const Text('Dispatch')),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(ShipmentDispatchRequested(
        shipment.id,
        trackingNumber: number.text.trim().isEmpty ? null : number.text.trim(),
        trackingUrl: url.text.trim().isEmpty ? null : url.text.trim(),
      ));
    }
  }

  Future<void> _promptAdvance(BuildContext context, Shipment shipment) async {
    final bloc = context.read<ShipmentsBloc>();
    var status = shipment.deliveryStatus ?? Shipment.deliveryStatuses.first;
    final description = TextEditingController();
    final location = TextEditingController();
    final proof = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Advance delivery status'),
          content: SizedBox(
            width: 400,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: Shipment.deliveryStatuses.contains(status) ? status : null,
                  decoration: const InputDecoration(labelText: 'Delivery status'),
                  items: Shipment.deliveryStatuses
                      .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                      .toList(),
                  onChanged: (v) => setState(() => status = v ?? status),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: description,
                  decoration: const InputDecoration(labelText: 'Description (optional)'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: location,
                  decoration: const InputDecoration(labelText: 'Location (optional)'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: proof,
                  maxLines: 2,
                  decoration: const InputDecoration(
                    labelText: 'Proof (key=value per line, optional)',
                    helperText: 'e.g. otp=123456 for PICKED_UP, photoUrl=…',
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Advance')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final proofMap = <String, dynamic>{};
    for (final line in proof.text.split('\n')) {
      final idx = line.indexOf('=');
      if (idx <= 0) continue;
      proofMap[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
    }
    bloc.add(DeliveryAdvanceRequested(
      shipment.id,
      status,
      proof: proofMap.isEmpty ? null : proofMap,
      description: description.text.trim().isEmpty ? null : description.text.trim(),
      location: location.text.trim().isEmpty ? null : location.text.trim(),
    ));
  }

  Future<void> _promptAssign(BuildContext context, Shipment shipment) async {
    final bloc = context.read<ShipmentsBloc>();
    final agent = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Assign courier'),
        content: TextField(
          controller: agent,
          decoration: const InputDecoration(
            labelText: 'Agent id',
            helperText: 'Leave empty to auto-assign any APPROVED agent',
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Assign')),
        ],
      ),
    );
    if (ok == true) {
      bloc.add(CourierAssignRequested(
        shipment.id,
        agentId: agent.text.trim().isEmpty ? null : agent.text.trim(),
      ));
    }
  }

  void _showTracking(BuildContext context, Shipment shipment) {
    final bloc = context.read<ShipmentsBloc>();
    bloc.add(TrackingRequested(shipment.id));
    showDialog<void>(
      context: context,
      builder: (ctx) => BlocProvider.value(
        value: bloc,
        child: AlertDialog(
          title: Text('Tracking — ${shipment.id}'),
          content: SizedBox(
            width: 520,
            height: 360,
            child: BlocBuilder<ShipmentsBloc, ShipmentsState>(
              builder: (ctx, state) {
                if (state.trackingShipmentId != shipment.id) return const LoadingView();
                if (state.tracking.isEmpty) {
                  return const EmptyView('No tracking events yet.');
                }
                return ListView(
                  children: state.tracking
                      .map((e) => ListTile(
                            dense: true,
                            leading: const Icon(Icons.local_shipping, size: 18),
                            title: Text(e.status),
                            subtitle: Text([
                              if (e.description != null) e.description,
                              if (e.location != null) e.location,
                              formatInstant(e.occurredAt),
                            ].whereType<String>().join(' · ')),
                          ))
                      .toList(),
                );
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
