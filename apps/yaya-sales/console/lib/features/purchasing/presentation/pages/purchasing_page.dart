import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/domain/money.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/purchasing_entities.dart';
import '../bloc/purchasing_bloc.dart';

/// Purchasing: suppliers CRUD + purchase orders (create, place, cancel,
/// receive — partials ok, received-so-far shown per line).
class PurchasingPage extends StatelessWidget {
  const PurchasingPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<PurchasingBloc, PurchasingState>(
      listenWhen: (prev, next) =>
          next.status == PurchasingStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        if (state.status == PurchasingStatus.initial ||
            state.status == PurchasingStatus.loading) {
          return const LoadingView();
        }
        if (state.status == PurchasingStatus.failure && state.suppliers.isEmpty) {
          return ErrorView(
            state.error ?? 'Failed to load purchasing',
            onRetry: () =>
                context.read<PurchasingBloc>().add(const PurchasingRequested()),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            SectionHeader('Suppliers', actions: [
              FilledButton.tonalIcon(
                onPressed: () => _promptSupplier(context, null),
                icon: const Icon(Icons.add),
                label: const Text('Add supplier'),
              ),
            ]),
            if (state.suppliers.isEmpty)
              const EmptyView('No suppliers yet.')
            else
              DataTable(
                columns: const [
                  DataColumn(label: Text('Name')),
                  DataColumn(label: Text('Contact')),
                  DataColumn(label: Text('Actions')),
                ],
                rows: state.suppliers
                    .map((s) => DataRow(cells: [
                          DataCell(Text(s.name)),
                          DataCell(Text(s.contact.entries
                              .map((e) => '${e.key}: ${e.value}')
                              .join(' · '))),
                          DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                            IconButton(
                              tooltip: 'Edit',
                              icon: const Icon(Icons.edit, size: 18),
                              onPressed: () => _promptSupplier(context, s),
                            ),
                            IconButton(
                              tooltip: 'Delete',
                              icon: const Icon(Icons.delete_outline, size: 18),
                              onPressed: () async {
                                final bloc = context.read<PurchasingBloc>();
                                if (await confirmAction(context, 'Delete supplier',
                                    'Delete supplier ${s.name}?')) {
                                  bloc.add(SupplierDeleteRequested(s.id));
                                }
                              },
                            ),
                          ])),
                        ]))
                    .toList(),
              ),
            const Divider(height: 40),
            SectionHeader('Purchase orders', actions: [
              DropdownButton<String?>(
                value: state.statusFilter,
                hint: const Text('All statuses'),
                items: [
                  const DropdownMenuItem<String?>(child: Text('All statuses')),
                  ...PurchaseOrder.statuses
                      .map((s) => DropdownMenuItem<String?>(value: s, child: Text(s))),
                ],
                onChanged: (v) => context
                    .read<PurchasingBloc>()
                    .add(PurchasingRequested(statusFilter: v)),
              ),
              const SizedBox(width: 12),
              FilledButton.tonalIcon(
                onPressed: () => _promptCreatePo(context, state.suppliers),
                icon: const Icon(Icons.add),
                label: const Text('New PO'),
              ),
            ]),
            if (state.purchaseOrders.isEmpty)
              const EmptyView('No purchase orders match this filter.')
            else
              ...state.purchaseOrders.map((po) => _poCard(context, po, state.suppliers)),
          ],
        );
      },
    );
  }

  Widget _poCard(BuildContext context, PurchaseOrder po, List<Supplier> suppliers) {
    final currency = sl<StoreContext>().storeCurrency ?? 'USD';
    final supplier =
        suppliers.where((s) => s.id == po.supplierId).map((s) => s.name).firstOrNull;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                SelectableText(po.id,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                const SizedBox(width: 12),
                StatusChip(po.status),
                const SizedBox(width: 12),
                Text(supplier ?? po.supplierId),
                const Spacer(),
                if (po.status == 'DRAFT') ...[
                  FilledButton.tonal(
                    onPressed: () =>
                        context.read<PurchasingBloc>().add(PoPlaceRequested(po.id)),
                    child: const Text('Place'),
                  ),
                  const SizedBox(width: 8),
                ],
                if (po.status == 'DRAFT' || po.status == 'PLACED') ...[
                  OutlinedButton(
                    onPressed: () async {
                      final bloc = context.read<PurchasingBloc>();
                      if (await confirmAction(context, 'Cancel PO', 'Cancel PO ${po.id}?')) {
                        bloc.add(PoCancelRequested(po.id));
                      }
                    },
                    child: const Text('Cancel'),
                  ),
                  const SizedBox(width: 8),
                ],
                if (po.status == 'PLACED' || po.status == 'PARTIALLY_RECEIVED')
                  FilledButton.tonal(
                    onPressed: () => _promptReceive(context, po),
                    child: const Text('Receive'),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Text('Location: ${po.locationId} · expected ${formatInstant(po.expectedAt)}'),
            const SizedBox(height: 4),
            ...po.lines.map((l) => Text(
                '${l.sku} — ordered ${l.qty} @ ${formatMinor(l.unitCostMinor, currency)}'
                ' · received so far ${l.receivedQty}')),
          ],
        ),
      ),
    );
  }

  Future<void> _promptSupplier(BuildContext context, Supplier? existing) async {
    final bloc = context.read<PurchasingBloc>();
    final name = TextEditingController(text: existing?.name ?? '');
    final contact = TextEditingController(
      text: (existing?.contact ?? {}).entries.map((e) => '${e.key}=${e.value}').join('\n'),
    );
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(existing == null ? 'Add supplier' : 'Edit supplier'),
        content: SizedBox(
          width: 400,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(controller: name, decoration: const InputDecoration(labelText: 'Name')),
              const SizedBox(height: 8),
              TextField(
                controller: contact,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: 'Contact (key=value per line)',
                  helperText: 'e.g. phone=809…  email=…',
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Save')),
        ],
      ),
    );
    if (ok != true || name.text.trim().isEmpty) return;
    final contactMap = <String, dynamic>{};
    for (final line in contact.text.split('\n')) {
      final idx = line.indexOf('=');
      if (idx <= 0) continue;
      contactMap[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
    }
    bloc.add(SupplierSaveRequested(
        id: existing?.id, name: name.text.trim(), contact: contactMap));
  }

  Future<void> _promptCreatePo(BuildContext context, List<Supplier> suppliers) async {
    final bloc = context.read<PurchasingBloc>();
    if (suppliers.isEmpty) {
      showErrorSnack(context, 'Add a supplier first');
      return;
    }
    var supplierId = suppliers.first.id;
    final location = TextEditingController();
    final expected = TextEditingController();
    final lines = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('New purchase order'),
          content: SizedBox(
            width: 460,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: supplierId,
                  decoration: const InputDecoration(labelText: 'Supplier'),
                  items: suppliers
                      .map((s) => DropdownMenuItem(value: s.id, child: Text(s.name)))
                      .toList(),
                  onChanged: (v) => setState(() => supplierId = v ?? supplierId),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: location,
                  decoration: const InputDecoration(labelText: 'Receiving location id'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: expected,
                  decoration: const InputDecoration(
                    labelText: 'Expected at (ISO-8601, optional)',
                    hintText: '2026-08-01T00:00:00Z',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: lines,
                  maxLines: 5,
                  decoration: const InputDecoration(
                    labelText: 'Lines — one per line: sku,qty,unitCostMinor',
                    helperText: 'cost in minor units (1999 = 19.99)',
                    border: OutlineInputBorder(),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
          ],
        ),
      ),
    );
    if (ok != true || location.text.trim().isEmpty) return;
    final parsed = <PoLine>[];
    for (final line in lines.text.split('\n')) {
      final parts = line.split(',');
      if (parts.isEmpty || parts.first.trim().isEmpty) continue;
      parsed.add(PoLine(
        sku: parts[0].trim(),
        qty: parts.length > 1 ? int.tryParse(parts[1].trim()) ?? 0 : 0,
        unitCostMinor: parts.length > 2 ? int.tryParse(parts[2].trim()) ?? 0 : 0,
      ));
    }
    if (parsed.isEmpty) return;
    bloc.add(PoCreateRequested(
      supplierId: supplierId,
      locationId: location.text.trim(),
      lines: parsed,
      expectedAt: DateTime.tryParse(expected.text.trim()),
    ));
  }

  Future<void> _promptReceive(BuildContext context, PurchaseOrder po) async {
    final bloc = context.read<PurchasingBloc>();
    final currency = sl<StoreContext>().storeCurrency ?? 'USD';
    final qtys = {
      for (final line in po.lines)
        line.sku: TextEditingController(text: '${line.qty - line.receivedQty}'),
    };
    final costs = {
      for (final line in po.lines)
        line.sku: TextEditingController(text: '${line.unitCostMinor}'),
    };
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Receive PO ${po.id}'),
        content: SizedBox(
          width: 480,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Partial receipts are fine — 0 skips a line.'),
              const SizedBox(height: 8),
              ...po.lines.map((l) => Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: Row(
                      children: [
                        Expanded(
                            child: Text(
                                '${l.sku} (${l.receivedQty}/${l.qty} received)')),
                        SizedBox(
                          width: 80,
                          child: TextField(
                            controller: qtys[l.sku],
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(labelText: 'Qty'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        SizedBox(
                          width: 130,
                          child: TextField(
                            controller: costs[l.sku],
                            keyboardType: TextInputType.number,
                            decoration: InputDecoration(
                                labelText: 'Cost minor ($currency)'),
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
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Receive')),
        ],
      ),
    );
    if (ok != true) return;
    final lines = po.lines
        .map((l) => PoLine(
              sku: l.sku,
              qty: int.tryParse(qtys[l.sku]!.text) ?? 0,
              unitCostMinor: int.tryParse(costs[l.sku]!.text) ?? l.unitCostMinor,
            ))
        .where((l) => l.qty > 0)
        .toList();
    if (lines.isEmpty) return;
    bloc.add(PoReceiveRequested(po.id, lines));
  }
}
