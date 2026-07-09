import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/di/service_locator.dart';
import '../../../../core/domain/money.dart';
import '../../../../core/storage/store_context.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/pricing_entities.dart';
import '../bloc/pricing_bloc.dart';

/// Price lists (tiered rules editor) + promotions (kind/value/eligibility/code
/// window, toggle).
class PricingPage extends StatelessWidget {
  const PricingPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<PricingBloc, PricingState>(
      listenWhen: (prev, next) => next.status == PricingStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        switch (state.status) {
          case PricingStatus.initial:
          case PricingStatus.loading:
            return const LoadingView();
          case PricingStatus.failure:
            return ErrorView(
              state.error ?? 'Failed to load pricing',
              onRetry: () => context.read<PricingBloc>().add(const PricingRequested()),
            );
          case PricingStatus.loaded:
            return ListView(
              padding: const EdgeInsets.all(24),
              children: [
                SectionHeader('Price lists', actions: [
                  FilledButton.tonalIcon(
                    onPressed: () => _promptPriceList(context, null),
                    icon: const Icon(Icons.add),
                    label: const Text('New price list'),
                  ),
                ]),
                if (state.priceLists.isEmpty)
                  const EmptyView('No price lists yet.')
                else
                  ...state.priceLists.map((pl) => Card(
                        child: ListTile(
                          leading: const Icon(Icons.price_change),
                          title: Text([
                            if (pl.regionId != null) 'region=${pl.regionId}',
                            if (pl.channelId != null) 'channel=${pl.channelId}',
                            if (pl.customerGroup != null) 'group=${pl.customerGroup}',
                            if (pl.regionId == null &&
                                pl.channelId == null &&
                                pl.customerGroup == null)
                              'store-wide',
                          ].join(' · ')),
                          subtitle: Text(
                              '${pl.rules.length} rule(s) · ${pl.active ? 'active' : 'inactive'}'),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Switch(
                                value: pl.active,
                                onChanged: (v) => context
                                    .read<PricingBloc>()
                                    .add(PriceListUpdateRequested(pl.id, active: v)),
                              ),
                              IconButton(
                                tooltip: 'Edit rules',
                                icon: const Icon(Icons.edit),
                                onPressed: () => _promptPriceList(context, pl),
                              ),
                            ],
                          ),
                        ),
                      )),
                const Divider(height: 40),
                SectionHeader('Promotions', actions: [
                  FilledButton.tonalIcon(
                    onPressed: () => _promptPromotion(context),
                    icon: const Icon(Icons.add),
                    label: const Text('New promotion'),
                  ),
                ]),
                if (state.promotions.isEmpty)
                  const EmptyView('No promotions yet.')
                else
                  DataTable(
                    columns: const [
                      DataColumn(label: Text('Kind')),
                      DataColumn(label: Text('Value')),
                      DataColumn(label: Text('Eligibility')),
                      DataColumn(label: Text('Code')),
                      DataColumn(label: Text('Active')),
                    ],
                    rows: state.promotions
                        .map((p) => DataRow(cells: [
                              DataCell(StatusChip(p.kind)),
                              DataCell(Text(_promoValue(p))),
                              DataCell(Text(_promoEligibility(p))),
                              DataCell(Text(p.code ?? '—')),
                              DataCell(Switch(
                                value: p.active,
                                onChanged: (v) => context
                                    .read<PricingBloc>()
                                    .add(PromotionToggleRequested(p.id, v)),
                              )),
                            ]))
                        .toList(),
                  ),
              ],
            );
        }
      },
    );
  }

  String _promoValue(Promotion p) {
    final currency = sl<StoreContext>().storeCurrency ?? '';
    if (p.bps != null) return '${(p.bps! / 100).toStringAsFixed(2)}% off';
    if (p.amountMinor != null) return '${formatMinor(p.amountMinor!, currency)} off';
    return '—';
  }

  String _promoEligibility(Promotion p) {
    final currency = sl<StoreContext>().storeCurrency ?? '';
    final parts = <String>[
      if (p.minSubtotalMinor != null)
        'min subtotal ${formatMinor(p.minSubtotalMinor!, currency)}',
      if (p.skus.isNotEmpty) 'skus: ${p.skus.join(', ')}',
    ];
    return parts.isEmpty ? 'any cart' : parts.join(' · ');
  }

  Future<void> _promptPriceList(BuildContext context, PriceList? existing) async {
    final bloc = context.read<PricingBloc>();
    final region = TextEditingController(text: existing?.regionId ?? '');
    final channel = TextEditingController(text: existing?.channelId ?? '');
    final group = TextEditingController(text: existing?.customerGroup ?? '');
    final rules = TextEditingController(
      text: (existing?.rules ?? [])
          .map((r) => '${r.sku},${r.minQty ?? ''},${r.priceMinor}')
          .join('\n'),
    );
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(existing == null ? 'New price list' : 'Edit price list rules'),
        content: SizedBox(
          width: 480,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (existing == null) ...[
                TextField(
                  controller: region,
                  decoration: const InputDecoration(labelText: 'Region id (optional)'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: channel,
                  decoration: const InputDecoration(labelText: 'Channel id (optional)'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: group,
                  decoration: const InputDecoration(labelText: 'Customer group (optional)'),
                ),
                const SizedBox(height: 8),
              ],
              TextField(
                controller: rules,
                maxLines: 8,
                decoration: const InputDecoration(
                  labelText: 'Rules — one per line: sku,minQty,priceMinor',
                  helperText: 'minQty empty = base price. Price in minor units (1999 = 19.99)',
                  border: OutlineInputBorder(),
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
    if (ok != true) return;
    final parsed = <PriceRule>[];
    for (final line in rules.text.split('\n')) {
      final parts = line.split(',');
      if (parts.isEmpty || parts.first.trim().isEmpty) continue;
      parsed.add(PriceRule(
        sku: parts[0].trim(),
        minQty: parts.length > 1 && parts[1].trim().isNotEmpty
            ? int.tryParse(parts[1].trim())
            : null,
        priceMinor: parts.length > 2 ? int.tryParse(parts[2].trim()) ?? 0 : 0,
      ));
    }
    if (existing == null) {
      bloc.add(PriceListCreateRequested(
        regionId: region.text.trim().isEmpty ? null : region.text.trim(),
        channelId: channel.text.trim().isEmpty ? null : channel.text.trim(),
        customerGroup: group.text.trim().isEmpty ? null : group.text.trim(),
        rules: parsed,
      ));
    } else {
      bloc.add(PriceListUpdateRequested(existing.id, rules: parsed));
    }
  }

  Future<void> _promptPromotion(BuildContext context) async {
    final bloc = context.read<PricingBloc>();
    var kind = Promotion.kinds.first;
    final bps = TextEditingController();
    final amount = TextEditingController();
    final minSubtotal = TextEditingController();
    final skus = TextEditingController();
    final code = TextEditingController();
    final startsAt = TextEditingController();
    final endsAt = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('New promotion'),
          content: SizedBox(
            width: 440,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  DropdownButtonFormField<String>(
                    initialValue: kind,
                    decoration: const InputDecoration(labelText: 'Kind'),
                    items: Promotion.kinds
                        .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                        .toList(),
                    onChanged: (v) => setState(() => kind = v ?? kind),
                  ),
                  const SizedBox(height: 8),
                  if (kind == 'PERCENT_OFF')
                    TextField(
                      controller: bps,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Value (bps)',
                        helperText: '1000 = 10%',
                      ),
                    )
                  else
                    TextField(
                      controller: amount,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Amount off (minor units)',
                        helperText: '500 = 5.00',
                      ),
                    ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: minSubtotal,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Min subtotal (minor units, optional)',
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: skus,
                    decoration: const InputDecoration(
                      labelText: 'Eligible SKUs (comma-separated, optional)',
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: code,
                    decoration: const InputDecoration(labelText: 'Code (optional)'),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: startsAt,
                    decoration: const InputDecoration(
                      labelText: 'Starts at (ISO-8601, optional)',
                      hintText: '2026-07-01T00:00:00Z',
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: endsAt,
                    decoration: const InputDecoration(
                      labelText: 'Ends at (ISO-8601, optional)',
                    ),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Create')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    bloc.add(PromotionCreateRequested(
      kind: kind,
      bps: kind == 'PERCENT_OFF' ? int.tryParse(bps.text) : null,
      amountMinor: kind == 'AMOUNT_OFF' ? int.tryParse(amount.text) : null,
      minSubtotalMinor:
          minSubtotal.text.trim().isEmpty ? null : int.tryParse(minSubtotal.text.trim()),
      skus: skus.text
          .split(',')
          .map((s) => s.trim())
          .where((s) => s.isNotEmpty)
          .toList(),
      code: code.text.trim().isEmpty ? null : code.text.trim(),
      startsAt: DateTime.tryParse(startsAt.text.trim()),
      endsAt: DateTime.tryParse(endsAt.text.trim()),
    ));
  }
}
