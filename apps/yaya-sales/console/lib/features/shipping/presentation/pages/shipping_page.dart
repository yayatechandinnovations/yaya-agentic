import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/shipping_entities.dart';
import '../bloc/shipping_bloc.dart';

/// Shipping configuration: zones, methods, rates (rule editor), carriers,
/// delivery slots.
class ShippingPage extends StatelessWidget {
  const ShippingPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<ShippingBloc, ShippingState>(
      listenWhen: (prev, next) =>
          next.status == ShippingStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        if (state.status == ShippingStatus.initial ||
            state.status == ShippingStatus.loading) {
          return const LoadingView();
        }
        if (state.status == ShippingStatus.failure && state.zones.isEmpty) {
          return ErrorView(
            state.error ?? 'Failed to load shipping config',
            onRetry: () => context.read<ShippingBloc>().add(const ShippingRequested()),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            _zones(context, state),
            const Divider(height: 40),
            _methods(context, state),
            const Divider(height: 40),
            _rates(context, state),
            const Divider(height: 40),
            _carriers(context, state),
            const Divider(height: 40),
            _slots(context, state),
          ],
        );
      },
    );
  }

  Widget _zones(BuildContext context, ShippingState state) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Zones', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptZone(context),
            icon: const Icon(Icons.add),
            label: const Text('Add zone'),
          ),
        ]),
        if (state.zones.isEmpty)
          const EmptyView('No zones yet.')
        else
          ...state.zones.map((z) => ListTile(
                dense: true,
                leading: const Icon(Icons.public),
                title: Text(z.name),
                subtitle: Text(z.regions
                    .map((r) => [
                          if (r.country != null) r.country,
                          if (r.region != null) r.region,
                          if (r.postalPrefix != null) 'postal ${r.postalPrefix}*',
                        ].join(' / '))
                    .join(' · ')),
                trailing: SelectableText(z.id, style: const TextStyle(fontSize: 11)),
              )),
      ],
    );
  }

  Widget _methods(BuildContext context, ShippingState state) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Delivery methods', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptMethod(context),
            icon: const Icon(Icons.add),
            label: const Text('Add method'),
          ),
        ]),
        if (state.methods.isEmpty)
          const EmptyView('No delivery methods yet.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Kind')),
              DataColumn(label: Text('Label')),
              DataColumn(label: Text('Active')),
              DataColumn(label: Text('Id')),
            ],
            rows: state.methods
                .map((m) => DataRow(cells: [
                      DataCell(StatusChip(m.kind)),
                      DataCell(Text(m.label)),
                      DataCell(Switch(
                        value: m.active,
                        onChanged: (v) => context
                            .read<ShippingBloc>()
                            .add(MethodActiveToggled(m.id, v)),
                      )),
                      DataCell(SelectableText(m.id, style: const TextStyle(fontSize: 11))),
                    ]))
                .toList(),
          ),
      ],
    );
  }

  Widget _rates(BuildContext context, ShippingState state) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Rates', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptRate(context, state),
            icon: const Icon(Icons.add),
            label: const Text('Add rate'),
          ),
        ]),
        if (state.rates.isEmpty)
          const EmptyView('No rates yet — add zones and methods first.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Zone')),
              DataColumn(label: Text('Method')),
              DataColumn(label: Text('Rule')),
              DataColumn(label: Text('Handling')),
              DataColumn(label: Text('Transit')),
            ],
            rows: state.rates.map((r) {
              final zone = state.zones
                  .where((z) => z.id == r.zoneId)
                  .map((z) => z.name)
                  .firstOrNull;
              final method = state.methods
                  .where((m) => m.id == r.methodId)
                  .map((m) => m.label)
                  .firstOrNull;
              return DataRow(cells: [
                DataCell(Text(zone ?? r.zoneId)),
                DataCell(Text(method ?? r.methodId)),
                DataCell(Text(r.ruleSummary)),
                DataCell(MoneyText(r.handlingFee)),
                DataCell(Text(r.transit == null
                    ? '—'
                    : '${r.transit!.minDays}–${r.transit!.maxDays} d')),
              ]);
            }).toList(),
          ),
      ],
    );
  }

  Widget _carriers(BuildContext context, ShippingState state) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Carriers', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptCarrier(context),
            icon: const Icon(Icons.add),
            label: const Text('Add carrier'),
          ),
        ]),
        if (state.carriers.isEmpty)
          const EmptyView('No carriers yet.')
        else
          ...state.carriers.map((c) => ListTile(
                dense: true,
                leading: const Icon(Icons.local_shipping),
                title: Text(c.name),
                subtitle: Text(c.kind),
                trailing: SelectableText(c.id, style: const TextStyle(fontSize: 11)),
              )),
      ],
    );
  }

  Widget _slots(BuildContext context, ShippingState state) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Delivery slots', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptSlot(context, state),
            icon: const Icon(Icons.add),
            label: const Text('Add slot window'),
          ),
        ]),
        const Text(
          'Slot windows created this session (the admin API has no slot listing).',
          style: TextStyle(color: Colors.black54, fontSize: 12),
        ),
        const SizedBox(height: 8),
        if (state.createdSlots.isEmpty)
          const EmptyView('No slot windows created yet.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Location')),
              DataColumn(label: Text('Method')),
              DataColumn(label: Text('Window')),
              DataColumn(label: Text('Capacity'), numeric: true),
              DataColumn(label: Text('Booked'), numeric: true),
            ],
            rows: state.createdSlots
                .map((s) => DataRow(cells: [
                      DataCell(Text(s.locationId)),
                      DataCell(Text(s.methodId)),
                      DataCell(Text(
                          '${formatInstant(s.startsAt)} → ${formatInstant(s.endsAt)}')),
                      DataCell(Text('${s.capacity}')),
                      DataCell(Text('${s.booked}')),
                    ]))
                .toList(),
          ),
      ],
    );
  }

  Future<void> _promptZone(BuildContext context) async {
    final bloc = context.read<ShippingBloc>();
    final name = TextEditingController();
    final matchers = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add zone'),
        content: SizedBox(
          width: 440,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(controller: name, decoration: const InputDecoration(labelText: 'Name')),
              const SizedBox(height: 8),
              TextField(
                controller: matchers,
                maxLines: 4,
                decoration: const InputDecoration(
                  labelText: 'Matchers — one per line: country,region,postalPrefix',
                  helperText: 'Empty field matches anything. e.g. "DO,," or "DO,Santo Domingo,"',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
        ],
      ),
    );
    if (ok != true || name.text.trim().isEmpty) return;
    final regions = <RegionMatch>[];
    for (final line in matchers.text.split('\n')) {
      if (line.trim().isEmpty) continue;
      final parts = line.split(',');
      String? part(int i) =>
          parts.length > i && parts[i].trim().isNotEmpty ? parts[i].trim() : null;
      regions.add(RegionMatch(country: part(0), region: part(1), postalPrefix: part(2)));
    }
    bloc.add(ZoneAddRequested(name.text.trim(), regions));
  }

  Future<void> _promptMethod(BuildContext context) async {
    final bloc = context.read<ShippingBloc>();
    var kind = DeliveryMethod.kinds.first;
    final label = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Add delivery method'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: kind,
                decoration: const InputDecoration(labelText: 'Kind'),
                items: DeliveryMethod.kinds
                    .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                    .toList(),
                onChanged: (v) => setState(() => kind = v ?? kind),
              ),
              const SizedBox(height: 8),
              TextField(
                  controller: label, decoration: const InputDecoration(labelText: 'Label')),
            ],
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
          ],
        ),
      ),
    );
    if (ok == true && label.text.trim().isNotEmpty) {
      bloc.add(MethodAddRequested(kind, label.text.trim()));
    }
  }

  Future<void> _promptCarrier(BuildContext context) async {
    final bloc = context.read<ShippingBloc>();
    final kind = TextEditingController(text: 'manual');
    final name = TextEditingController();
    final secret = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Add carrier'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: kind,
              decoration: const InputDecoration(
                labelText: 'Kind',
                helperText: 'manual or in-house',
              ),
            ),
            const SizedBox(height: 8),
            TextField(controller: name, decoration: const InputDecoration(labelText: 'Name')),
            const SizedBox(height: 8),
            TextField(
              controller: secret,
              decoration:
                  const InputDecoration(labelText: 'Webhook secret (optional)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
        ],
      ),
    );
    if (ok == true && name.text.trim().isNotEmpty) {
      bloc.add(CarrierAddRequested(
        kind.text.trim(),
        name.text.trim(),
        secret.text.trim().isEmpty ? null : secret.text.trim(),
      ));
    }
  }

  Future<void> _promptRate(BuildContext context, ShippingState state) async {
    final bloc = context.read<ShippingBloc>();
    if (state.zones.isEmpty || state.methods.isEmpty) {
      showErrorSnack(context, 'Add a zone and a delivery method first');
      return;
    }
    var zoneId = state.zones.first.id;
    var methodId = state.methods.first.id;
    String? carrierId;
    var ruleType = RateRuleDraft.types.first;
    final amount = TextEditingController();
    final tiers = TextEditingController();
    final threshold = TextEditingController();
    final otherwiseAmount = TextEditingController();
    final handling = TextEditingController();
    final minDays = TextEditingController();
    final maxDays = TextEditingController();
    final serviceLevel = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Add rate'),
          content: SizedBox(
            width: 480,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  DropdownButtonFormField<String>(
                    initialValue: zoneId,
                    decoration: const InputDecoration(labelText: 'Zone'),
                    items: state.zones
                        .map((z) => DropdownMenuItem(value: z.id, child: Text(z.name)))
                        .toList(),
                    onChanged: (v) => setState(() => zoneId = v ?? zoneId),
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String>(
                    initialValue: methodId,
                    decoration: const InputDecoration(labelText: 'Method'),
                    items: state.methods
                        .map((m) => DropdownMenuItem(value: m.id, child: Text(m.label)))
                        .toList(),
                    onChanged: (v) => setState(() => methodId = v ?? methodId),
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String?>(
                    initialValue: carrierId,
                    decoration: const InputDecoration(labelText: 'Carrier (optional)'),
                    items: [
                      const DropdownMenuItem<String?>(child: Text('None')),
                      ...state.carriers.map(
                          (c) => DropdownMenuItem<String?>(value: c.id, child: Text(c.name))),
                    ],
                    onChanged: (v) => setState(() => carrierId = v),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: serviceLevel,
                    decoration:
                        const InputDecoration(labelText: 'Service level (optional)'),
                  ),
                  const SizedBox(height: 8),
                  DropdownButtonFormField<String>(
                    initialValue: ruleType,
                    decoration: const InputDecoration(labelText: 'Rule type'),
                    items: RateRuleDraft.types
                        .map((t) => DropdownMenuItem(value: t, child: Text(t)))
                        .toList(),
                    onChanged: (v) => setState(() => ruleType = v ?? ruleType),
                  ),
                  const SizedBox(height: 8),
                  if (ruleType == 'FLAT')
                    TextField(
                      controller: amount,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Amount (minor units)',
                        helperText: '1999 = 19.99 in the store currency',
                      ),
                    ),
                  if (ruleType == 'BY_WEIGHT' || ruleType == 'BY_PRICE')
                    TextField(
                      controller: tiers,
                      maxLines: 4,
                      decoration: InputDecoration(
                        labelText: 'Tiers — one per line: upTo,amountMinor',
                        helperText: ruleType == 'BY_WEIGHT'
                            ? 'upTo in grams'
                            : 'upTo in cart-value minor units',
                        border: const OutlineInputBorder(),
                      ),
                    ),
                  if (ruleType == 'FREE_OVER') ...[
                    TextField(
                      controller: threshold,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Free over threshold (minor units)',
                      ),
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: otherwiseAmount,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Otherwise FLAT amount (minor units)',
                      ),
                    ),
                  ],
                  const SizedBox(height: 8),
                  TextField(
                    controller: handling,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                        labelText: 'Handling fee (minor units, optional)'),
                  ),
                  const SizedBox(height: 8),
                  Row(children: [
                    Expanded(
                      child: TextField(
                        controller: minDays,
                        keyboardType: TextInputType.number,
                        decoration:
                            const InputDecoration(labelText: 'Transit min (days)'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: maxDays,
                        keyboardType: TextInputType.number,
                        decoration:
                            const InputDecoration(labelText: 'Transit max (days)'),
                      ),
                    ),
                  ]),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final parsedTiers = <RateTier>[];
    for (final line in tiers.text.split('\n')) {
      final parts = line.split(',');
      if (parts.length < 2 || parts[0].trim().isEmpty) continue;
      parsedTiers.add(RateTier(
        upTo: int.tryParse(parts[0].trim()) ?? 0,
        amountMinor: int.tryParse(parts[1].trim()) ?? 0,
      ));
    }
    final rule = switch (ruleType) {
      'FLAT' => RateRuleDraft(type: 'FLAT', amountMinor: int.tryParse(amount.text) ?? 0),
      'BY_WEIGHT' => RateRuleDraft(type: 'BY_WEIGHT', tiers: parsedTiers),
      'BY_PRICE' => RateRuleDraft(type: 'BY_PRICE', tiers: parsedTiers),
      _ => RateRuleDraft(
          type: 'FREE_OVER',
          thresholdMinor: int.tryParse(threshold.text) ?? 0,
          otherwise: RateRuleDraft(
              type: 'FLAT', amountMinor: int.tryParse(otherwiseAmount.text) ?? 0),
        ),
    };
    final min = int.tryParse(minDays.text);
    final max = int.tryParse(maxDays.text);
    bloc.add(RateAddRequested(
      zoneId: zoneId,
      methodId: methodId,
      carrierId: carrierId,
      serviceLevel: serviceLevel.text.trim().isEmpty ? null : serviceLevel.text.trim(),
      rule: rule,
      handlingFeeMinor: int.tryParse(handling.text),
      transit: min != null && max != null
          ? TransitEstimate(minDays: min, maxDays: max)
          : null,
    ));
  }

  Future<void> _promptSlot(BuildContext context, ShippingState state) async {
    final bloc = context.read<ShippingBloc>();
    final location = TextEditingController();
    var methodId = state.methods.isEmpty ? null : state.methods.first.id;
    final starts = TextEditingController();
    final ends = TextEditingController();
    final capacity = TextEditingController(text: '10');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Add slot window'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: location,
                  decoration: const InputDecoration(labelText: 'Location id'),
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  initialValue: methodId,
                  decoration: const InputDecoration(labelText: 'Delivery method'),
                  items: state.methods
                      .map((m) => DropdownMenuItem(value: m.id, child: Text(m.label)))
                      .toList(),
                  onChanged: (v) => setState(() => methodId = v),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: starts,
                  decoration: const InputDecoration(
                    labelText: 'Starts at (ISO-8601)',
                    hintText: '2026-07-10T09:00:00Z',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: ends,
                  decoration: const InputDecoration(
                    labelText: 'Ends at (ISO-8601)',
                    hintText: '2026-07-10T12:00:00Z',
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: capacity,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'Capacity'),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Add')),
          ],
        ),
      ),
    );
    if (ok != true || methodId == null) return;
    final startsAt = DateTime.tryParse(starts.text.trim());
    final endsAt = DateTime.tryParse(ends.text.trim());
    if (startsAt == null || endsAt == null || location.text.trim().isEmpty) {
      if (context.mounted) showErrorSnack(context, 'Location and valid ISO dates required');
      return;
    }
    bloc.add(SlotAddRequested(
      locationId: location.text.trim(),
      methodId: methodId!,
      startsAt: startsAt,
      endsAt: endsAt,
      capacity: int.tryParse(capacity.text) ?? 0,
    ));
  }
}
