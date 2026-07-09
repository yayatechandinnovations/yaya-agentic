import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/domain/money.dart';
import '../../../../core/widgets/widgets.dart';
import '../../../catalog/domain/entities/catalog_entities.dart';
import '../../../orders/domain/entities/order_entities.dart';
import '../../domain/entities/draft_entities.dart';
import '../bloc/drafts_bloc.dart';

/// Draft orders: build lines (SKU picker from the catalogs, qty, optional
/// price override), edit while DRAFT, send (tender choice) → hosted URL /
/// instructions outcome, cancel.
class DraftsPage extends StatelessWidget {
  const DraftsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<DraftsBloc, DraftsState>(
      listener: (context, state) {
        if (state.status == DraftsStatus.failure && state.error != null) {
          showErrorSnack(context, state.error!);
        }
        if (state.lastOutcome != null) {
          _showOutcome(context, state.lastOutcome!);
          context.read<DraftsBloc>().add(const DraftOutcomeDismissed());
        }
      },
      builder: (context, state) {
        return Scaffold(
          floatingActionButton: FloatingActionButton.extended(
            onPressed: () => _promptEditor(context, null),
            icon: const Icon(Icons.add),
            label: const Text('New draft'),
          ),
          body: _body(context, state),
        );
      },
    );
  }

  Widget _body(BuildContext context, DraftsState state) {
    switch (state.status) {
      case DraftsStatus.initial:
      case DraftsStatus.loading:
        return const LoadingView();
      case DraftsStatus.failure:
        return ErrorView(
          state.error ?? 'Failed to load drafts',
          onRetry: () => context.read<DraftsBloc>().add(const DraftsRequested()),
        );
      case DraftsStatus.loaded:
        if (state.drafts.isEmpty) {
          return const EmptyView('No draft orders — build one for a customer.');
        }
        return ListView.separated(
          padding: const EdgeInsets.all(16),
          itemCount: state.drafts.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (_, i) {
            final draft = state.drafts[i];
            return ListTile(
              leading: const Icon(Icons.edit_note),
              title: Text('${draft.id} · ${draft.customerRef ?? 'no customer'}'),
              subtitle: Text(
                  '${draft.lines.map((l) => '${l.sku}×${l.qty}').join(', ')} — '
                  '${formatMoney(draft.totals.grand)}'),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  OutlinedButton(
                    onPressed: () => _promptEditor(context, draft),
                    child: const Text('Edit lines'),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.tonal(
                    onPressed: () => _promptSend(context, draft),
                    child: const Text('Send'),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    tooltip: 'Cancel draft',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () async {
                      final bloc = context.read<DraftsBloc>();
                      if (await confirmAction(
                          context, 'Cancel draft', 'Cancel draft ${draft.id}?')) {
                        bloc.add(DraftCancelRequested(draft.id));
                      }
                    },
                  ),
                ],
              ),
            );
          },
        );
    }
  }

  Future<void> _promptEditor(BuildContext context, Order? existing) async {
    final bloc = context.read<DraftsBloc>();
    bloc.add(const DraftSkusRequested());
    final customer = TextEditingController(text: existing?.customerRef ?? '');
    final region = TextEditingController();
    final note = TextEditingController();
    final rows = <_LineRow>[
      if (existing == null)
        _LineRow()
      else
        ...existing.lines.map((l) => _LineRow(sku: l.sku, qty: l.qty)),
    ];
    await showDialog<void>(
      context: context,
      builder: (ctx) => BlocProvider.value(
        value: bloc,
        child: StatefulBuilder(
          builder: (ctx, setState) => AlertDialog(
            title: Text(existing == null ? 'New draft order' : 'Edit draft ${existing.id}'),
            content: SizedBox(
              width: 560,
              child: SingleChildScrollView(
                child: BlocBuilder<DraftsBloc, DraftsState>(
                  builder: (ctx, state) => Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (existing == null) ...[
                        TextField(
                          controller: customer,
                          decoration:
                              const InputDecoration(labelText: 'Customer ref (optional)'),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: region,
                          decoration:
                              const InputDecoration(labelText: 'Region id (optional)'),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: note,
                          decoration: const InputDecoration(labelText: 'Note (optional)'),
                        ),
                        const SizedBox(height: 12),
                      ],
                      ...rows.map((row) => Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: Row(
                              children: [
                                Expanded(
                                  flex: 2,
                                  child: _skuField(state.skuOptions, row),
                                ),
                                const SizedBox(width: 8),
                                SizedBox(
                                  width: 80,
                                  child: TextField(
                                    controller: row.qtyCtrl,
                                    keyboardType: TextInputType.number,
                                    decoration: const InputDecoration(labelText: 'Qty'),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                SizedBox(
                                  width: 140,
                                  child: TextField(
                                    controller: row.priceCtrl,
                                    keyboardType: TextInputType.number,
                                    decoration: const InputDecoration(
                                      labelText: 'Override (minor)',
                                      helperText: 'empty = list price',
                                    ),
                                  ),
                                ),
                                IconButton(
                                  icon: const Icon(Icons.delete_outline, size: 18),
                                  onPressed: rows.length == 1
                                      ? null
                                      : () => setState(() => rows.remove(row)),
                                ),
                              ],
                            ),
                          )),
                      Align(
                        alignment: Alignment.centerLeft,
                        child: TextButton.icon(
                          onPressed: () => setState(() => rows.add(_LineRow())),
                          icon: const Icon(Icons.add),
                          label: const Text('Add line'),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
              FilledButton(
                onPressed: () {
                  final lines = rows
                      .map((r) => DraftLine(
                            sku: r.skuCtrl.text.trim(),
                            qty: int.tryParse(r.qtyCtrl.text) ?? 0,
                            unitPriceMinor: r.priceCtrl.text.trim().isEmpty
                                ? null
                                : int.tryParse(r.priceCtrl.text.trim()),
                          ))
                      .where((l) => l.sku.isNotEmpty && l.qty > 0)
                      .toList();
                  if (lines.isEmpty) return;
                  if (existing == null) {
                    bloc.add(DraftCreateRequested(
                      customerRef:
                          customer.text.trim().isEmpty ? null : customer.text.trim(),
                      regionId: region.text.trim().isEmpty ? null : region.text.trim(),
                      note: note.text.trim().isEmpty ? null : note.text.trim(),
                      lines: lines,
                    ));
                  } else {
                    bloc.add(DraftUpdateRequested(existing.id, lines));
                  }
                  Navigator.pop(ctx);
                },
                child: const Text('Save'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _skuField(List<Variant> options, _LineRow row) {
    return Autocomplete<Variant>(
      displayStringForOption: (v) => v.sku,
      optionsBuilder: (text) => options.where((v) =>
          v.sku.toLowerCase().contains(text.text.toLowerCase()) ||
          v.label.toLowerCase().contains(text.text.toLowerCase())),
      onSelected: (v) => row.skuCtrl.text = v.sku,
      fieldViewBuilder: (ctx, controller, focus, onSubmit) {
        if (controller.text.isEmpty && row.skuCtrl.text.isNotEmpty) {
          controller.text = row.skuCtrl.text;
        }
        controller.addListener(() => row.skuCtrl.text = controller.text);
        return TextField(
          controller: controller,
          focusNode: focus,
          decoration: const InputDecoration(
            labelText: 'SKU',
            helperText: 'type to search the catalog',
          ),
        );
      },
      optionsViewBuilder: (ctx, onSelected, opts) => Align(
        alignment: Alignment.topLeft,
        child: Material(
          elevation: 4,
          child: SizedBox(
            width: 360,
            height: 220,
            child: ListView(
              children: opts
                  .map((v) => ListTile(
                        dense: true,
                        title: Text(v.sku),
                        subtitle: Text('${v.label} — ${formatMoney(v.price)}'),
                        onTap: () => onSelected(v),
                      ))
                  .toList(),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _promptSend(BuildContext context, Order draft) async {
    final bloc = context.read<DraftsBloc>();
    String? tender;
    var applyStoreCredit = false;
    final customer = TextEditingController(text: draft.customerRef ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text('Send draft ${draft.id}'),
          content: SizedBox(
            width: 380,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String?>(
                  initialValue: tender,
                  decoration: const InputDecoration(
                    labelText: 'Tender',
                    helperText: 'Empty = region default',
                  ),
                  items: [
                    const DropdownMenuItem<String?>(child: Text('Default')),
                    ...Tender.kinds
                        .map((k) => DropdownMenuItem<String?>(value: k, child: Text(k))),
                  ],
                  onChanged: (v) => setState(() => tender = v),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: customer,
                  decoration: const InputDecoration(labelText: 'Customer ref (optional)'),
                ),
                SwitchListTile(
                  title: const Text('Apply store credit'),
                  value: applyStoreCredit,
                  onChanged: (v) => setState(() => applyStoreCredit = v),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Send')),
          ],
        ),
      ),
    );
    if (ok == true) {
      bloc.add(DraftSendRequested(
        draft.id,
        tender: tender,
        applyStoreCredit: applyStoreCredit,
        returnUrl: '${Uri.base.origin}/orders',
        customerRef: customer.text.trim().isEmpty ? null : customer.text.trim(),
      ));
    }
  }

  void _showOutcome(BuildContext context, SendOutcome outcome) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Send outcome — ${outcome.status}'),
        content: SizedBox(
          width: 460,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              KeyValueRow('Order', outcome.orderId),
              if (outcome.paymentUrl != null)
                KeyValueRow('Payment URL', outcome.paymentUrl!),
              if (outcome.clientSecret != null)
                const KeyValueRow('Payment', 'client-side intent (see integration)'),
              if (outcome.instructions != null)
                KeyValueRow(
                    'Instructions',
                    outcome.instructions!.entries
                        .map((e) => '${e.key}: ${e.value}')
                        .join('\n')),
              if (outcome.status == 'INSUFFICIENT_STOCK')
                KeyValueRow('Insufficient stock',
                    '${outcome.insufficientSku} — available ${outcome.available}'),
            ],
          ),
        ),
        actions: [
          FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }
}

class _LineRow {
  _LineRow({String sku = '', int? qty})
      : skuCtrl = TextEditingController(text: sku),
        qtyCtrl = TextEditingController(text: qty == null ? '1' : '$qty'),
        priceCtrl = TextEditingController();

  final TextEditingController skuCtrl;
  final TextEditingController qtyCtrl;
  final TextEditingController priceCtrl;
}
