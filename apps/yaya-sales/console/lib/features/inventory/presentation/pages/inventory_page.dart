import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/inventory_entities.dart';
import '../bloc/inventory_bloc.dart';

/// Inventory levels + per-SKU ledger + movement actions (receive / adjust /
/// transfer / reorder policy / backorder policy).
class InventoryPage extends StatefulWidget {
  const InventoryPage({super.key});

  @override
  State<InventoryPage> createState() => _InventoryPageState();
}

class _InventoryPageState extends State<InventoryPage> {
  final _location = TextEditingController();
  bool _lowOnly = false;

  @override
  void dispose() {
    _location.dispose();
    super.dispose();
  }

  void _reload() {
    context.read<InventoryBloc>().add(LevelsRequested(
          location: _location.text.trim().isEmpty ? null : _location.text.trim(),
          lowOnly: _lowOnly,
        ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _promptReceive(context, null),
        icon: const Icon(Icons.call_received),
        label: const Text('Receive'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: Row(
              children: [
                SizedBox(
                  width: 280,
                  child: TextField(
                    controller: _location,
                    decoration: const InputDecoration(
                      labelText: 'Location id filter',
                      isDense: true,
                    ),
                    onSubmitted: (_) => _reload(),
                  ),
                ),
                const SizedBox(width: 16),
                FilterChip(
                  label: const Text('Low stock only'),
                  selected: _lowOnly,
                  onSelected: (v) {
                    setState(() => _lowOnly = v);
                    _reload();
                  },
                ),
                const SizedBox(width: 16),
                OutlinedButton.icon(
                  onPressed: _reload,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Refresh'),
                ),
                const Spacer(),
                OutlinedButton.icon(
                  onPressed: () => _promptTransfer(context),
                  icon: const Icon(Icons.swap_horiz),
                  label: const Text('Transfer'),
                ),
              ],
            ),
          ),
          Expanded(
            child: BlocConsumer<InventoryBloc, InventoryState>(
              listenWhen: (prev, next) =>
                  next.status == InventoryStatus.failure && next.error != null,
              listener: (context, state) => showErrorSnack(context, state.error!),
              builder: (context, state) {
                switch (state.status) {
                  case InventoryStatus.initial:
                  case InventoryStatus.loading:
                    return const LoadingView();
                  case InventoryStatus.failure:
                    return ErrorView(state.error ?? 'Failed to load levels',
                        onRetry: _reload);
                  case InventoryStatus.loaded:
                    if (state.levels.isEmpty) {
                      return const EmptyView('No stock levels — receive some inventory.');
                    }
                    return SingleChildScrollView(
                      padding: const EdgeInsets.all(16),
                      child: DataTable(
                        columns: const [
                          DataColumn(label: Text('SKU')),
                          DataColumn(label: Text('Location')),
                          DataColumn(label: Text('On hand'), numeric: true),
                          DataColumn(label: Text('Reserved'), numeric: true),
                          DataColumn(label: Text('Available'), numeric: true),
                          DataColumn(label: Text('Avg cost')),
                          DataColumn(label: Text('Reorder')),
                          DataColumn(label: Text('Backorder')),
                          DataColumn(label: Text('Actions')),
                        ],
                        rows: state.levels.map((l) => _row(context, l)).toList(),
                      ),
                    );
                }
              },
            ),
          ),
        ],
      ),
    );
  }

  DataRow _row(BuildContext context, StockLevel level) {
    return DataRow(
      color: level.low ? WidgetStatePropertyAll(Colors.orange.shade50) : null,
      cells: [
        DataCell(
          Text(level.sku, style: const TextStyle(fontWeight: FontWeight.w600)),
          onTap: () => _showLedger(context, level),
        ),
        DataCell(Text(level.locationId)),
        DataCell(Text('${level.onHand}')),
        DataCell(Text('${level.reserved}')),
        DataCell(Text('${level.available}')),
        DataCell(MoneyText(level.avgCost)),
        DataCell(Text('${level.reorderPoint} / ${level.reorderQty}')),
        DataCell(StatusChip(level.backorder)),
        DataCell(Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: 'Ledger',
              icon: const Icon(Icons.receipt_long, size: 18),
              onPressed: () => _showLedger(context, level),
            ),
            IconButton(
              tooltip: 'Receive',
              icon: const Icon(Icons.call_received, size: 18),
              onPressed: () => _promptReceive(context, level),
            ),
            IconButton(
              tooltip: 'Adjust',
              icon: const Icon(Icons.tune, size: 18),
              onPressed: () => _promptAdjust(context, level),
            ),
            IconButton(
              tooltip: 'Reorder policy',
              icon: const Icon(Icons.low_priority, size: 18),
              onPressed: () => _promptReorder(context, level),
            ),
            IconButton(
              tooltip: 'Backorder policy',
              icon: const Icon(Icons.pause_presentation, size: 18),
              onPressed: () => _promptBackorder(context, level),
            ),
          ],
        )),
      ],
    );
  }

  void _showLedger(BuildContext context, StockLevel level) {
    final bloc = context.read<InventoryBloc>();
    bloc.add(LedgerRequested(level.sku, location: level.locationId));
    showDialog<void>(
      context: context,
      builder: (ctx) => BlocProvider.value(
        value: bloc,
        child: AlertDialog(
          title: Text('Ledger — ${level.sku}'),
          content: SizedBox(
            width: 720,
            height: 420,
            child: BlocBuilder<InventoryBloc, InventoryState>(
              builder: (ctx, state) {
                if (state.ledgerSku != level.sku) return const LoadingView();
                if (state.ledger.isEmpty) {
                  return const EmptyView('No movements for this SKU.');
                }
                return SingleChildScrollView(
                  child: DataTable(
                    columns: const [
                      DataColumn(label: Text('When')),
                      DataColumn(label: Text('Kind')),
                      DataColumn(label: Text('Δ qty'), numeric: true),
                      DataColumn(label: Text('Unit cost')),
                      DataColumn(label: Text('Ref')),
                      DataColumn(label: Text('Reason')),
                    ],
                    rows: state.ledger
                        .map((m) => DataRow(cells: [
                              DataCell(Text(formatInstant(m.occurredAt))),
                              DataCell(StatusChip(m.kind)),
                              DataCell(Text('${m.qtyDelta}')),
                              DataCell(MoneyText(m.unitCost)),
                              DataCell(Text(
                                  m.refType == null ? '—' : '${m.refType}:${m.refId ?? ''}')),
                              DataCell(Text(m.reason ?? '—')),
                            ]))
                        .toList(),
                  ),
                );
              },
            ),
          ),
          actions: [
            FilledButton(
              onPressed: () {
                bloc.add(const LedgerClosed());
                Navigator.pop(ctx);
              },
              child: const Text('Close'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _promptReceive(BuildContext context, StockLevel? level) async {
    final bloc = context.read<InventoryBloc>();
    final sku = TextEditingController(text: level?.sku ?? '');
    final location = TextEditingController(text: level?.locationId ?? '');
    final qty = TextEditingController();
    final cost = TextEditingController();
    final ref = TextEditingController();
    final currency = sl<StoreContext>().storeCurrency ?? 'USD';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Receive stock'),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(controller: sku, decoration: const InputDecoration(labelText: 'SKU')),
              const SizedBox(height: 8),
              TextField(
                  controller: location,
                  decoration: const InputDecoration(labelText: 'Location id')),
              const SizedBox(height: 8),
              TextField(
                controller: qty,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Quantity'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: cost,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: 'Unit cost (minor units, $currency)',
                  helperText: '1999 = 19.99',
                ),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: ref,
                decoration:
                    const InputDecoration(labelText: 'Purchase order ref (optional)'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true), child: const Text('Receive')),
        ],
      ),
    );
    if (ok == true && sku.text.trim().isNotEmpty && location.text.trim().isNotEmpty) {
      bloc.add(ReceiveRequested(
        sku: sku.text.trim(),
        location: location.text.trim(),
        qty: int.tryParse(qty.text) ?? 0,
        unitCostMinor: int.tryParse(cost.text) ?? 0,
        currency: currency,
        poRef: ref.text.trim().isEmpty ? null : ref.text.trim(),
      ));
    }
  }

  Future<void> _promptAdjust(BuildContext context, StockLevel level) async {
    final bloc = context.read<InventoryBloc>();
    final delta = TextEditingController();
    final reason = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Adjust — ${level.sku} @ ${level.locationId}'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: delta,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Delta (±)',
                helperText: 'Negative removes stock',
              ),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: reason,
              decoration: const InputDecoration(labelText: 'Reason (required)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Adjust')),
        ],
      ),
    );
    if (ok == true && reason.text.trim().isNotEmpty) {
      bloc.add(AdjustRequested(
        sku: level.sku,
        location: level.locationId,
        delta: int.tryParse(delta.text) ?? 0,
        reason: reason.text.trim(),
      ));
    }
  }

  Future<void> _promptTransfer(BuildContext context) async {
    final bloc = context.read<InventoryBloc>();
    final sku = TextEditingController();
    final from = TextEditingController();
    final to = TextEditingController();
    final qty = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Transfer stock'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: sku, decoration: const InputDecoration(labelText: 'SKU')),
            const SizedBox(height: 8),
            TextField(
                controller: from,
                decoration: const InputDecoration(labelText: 'From location id')),
            const SizedBox(height: 8),
            TextField(
                controller: to, decoration: const InputDecoration(labelText: 'To location id')),
            const SizedBox(height: 8),
            TextField(
              controller: qty,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Quantity'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true), child: const Text('Transfer')),
        ],
      ),
    );
    if (ok == true && sku.text.trim().isNotEmpty) {
      bloc.add(TransferRequested(
        sku: sku.text.trim(),
        from: from.text.trim(),
        to: to.text.trim(),
        qty: int.tryParse(qty.text) ?? 0,
      ));
    }
  }

  Future<void> _promptReorder(BuildContext context, StockLevel level) async {
    final bloc = context.read<InventoryBloc>();
    final point = TextEditingController(text: '${level.reorderPoint}');
    final qty = TextEditingController(text: '${level.reorderQty}');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Reorder policy — ${level.sku} @ ${level.locationId}'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: point,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Reorder point'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: qty,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Reorder quantity'),
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
      bloc.add(ReorderPolicyRequested(
        sku: level.sku,
        location: level.locationId,
        point: int.tryParse(point.text) ?? 0,
        qty: int.tryParse(qty.text) ?? 0,
      ));
    }
  }

  Future<void> _promptBackorder(BuildContext context, StockLevel level) async {
    final bloc = context.read<InventoryBloc>();
    var policy = level.backorder;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text('Backorder policy — ${level.sku} @ ${level.locationId}'),
          content: DropdownButtonFormField<String>(
            initialValue: policy,
            items: const ['DENY', 'ALLOW', 'PREORDER']
                .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                .toList(),
            onChanged: (v) => setState(() => policy = v ?? policy),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Save')),
          ],
        ),
      ),
    );
    if (ok == true) {
      bloc.add(BackorderPolicyRequested(
        sku: level.sku,
        location: level.locationId,
        policy: policy,
      ));
    }
  }
}
