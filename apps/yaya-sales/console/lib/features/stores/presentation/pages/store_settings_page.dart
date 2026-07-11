import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/widgets/address_fields.dart';
import '../../../../core/widgets/widgets.dart';
import '../../domain/entities/store_entities.dart';
import '../bloc/store_settings_bloc.dart';

/// Store settings: detail + policies, locations, channels, regions.
class StoreSettingsPage extends StatelessWidget {
  const StoreSettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<StoreSettingsBloc, StoreSettingsState>(
      listenWhen: (prev, next) =>
          next.status == StoreSettingsStatus.failure && next.error != null,
      listener: (context, state) => showErrorSnack(context, state.error!),
      builder: (context, state) {
        if (state.status == StoreSettingsStatus.initial ||
            state.status == StoreSettingsStatus.loading) {
          return const LoadingView();
        }
        final store = state.store;
        if (store == null) {
          return ErrorView(
            state.error ?? 'Failed to load store',
            onRetry: () => context
                .read<StoreSettingsBloc>()
                .add(StoreSettingsRequested(state.storeId)),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(store.displayName, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 4),
            KeyValueRow('Store id', store.id),
            KeyValueRow('Tenant', store.tenantId),
            KeyValueRow('Default currency', store.defaultCurrency),
            const Divider(height: 32),
            _PoliciesSection(policies: store.policies),
            const Divider(height: 32),
            _LocationsSection(locations: state.locations),
            const Divider(height: 32),
            _ChannelsSection(channels: state.channels),
            const Divider(height: 32),
            _RegionsSection(regions: state.regions, defaultCurrency: store.defaultCurrency),
          ],
        );
      },
    );
  }
}

class _PoliciesSection extends StatefulWidget {
  const _PoliciesSection({required this.policies});

  final StorePolicies policies;

  @override
  State<_PoliciesSection> createState() => _PoliciesSectionState();
}

class _PoliciesSectionState extends State<_PoliciesSection> {
  late String _deduction = widget.policies.deductionPoint;
  late String _backorder = widget.policies.backorder;
  late final TextEditingController _softTtl =
      TextEditingController(text: '${widget.policies.softHoldTtlSeconds}');
  late final TextEditingController _checkoutTtl =
      TextEditingController(text: '${widget.policies.checkoutHoldTtlSeconds}');

  @override
  void dispose() {
    _softTtl.dispose();
    _checkoutTtl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionHeader('Policies'),
        Wrap(
          spacing: 16,
          runSpacing: 16,
          crossAxisAlignment: WrapCrossAlignment.end,
          children: [
            SizedBox(
              width: 220,
              child: DropdownButtonFormField<String>(
                initialValue: _deduction,
                decoration: const InputDecoration(labelText: 'Deduction point'),
                items: StorePolicies.deductionPoints
                    .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                    .toList(),
                onChanged: (v) => setState(() => _deduction = v ?? _deduction),
              ),
            ),
            SizedBox(
              width: 220,
              child: DropdownButtonFormField<String>(
                initialValue: _backorder,
                decoration: const InputDecoration(labelText: 'Backorder policy'),
                items: StorePolicies.backorderPolicies
                    .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                    .toList(),
                onChanged: (v) => setState(() => _backorder = v ?? _backorder),
              ),
            ),
            SizedBox(
              width: 180,
              child: TextField(
                controller: _softTtl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Soft hold TTL (s)'),
              ),
            ),
            SizedBox(
              width: 180,
              child: TextField(
                controller: _checkoutTtl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Checkout hold TTL (s)'),
              ),
            ),
            FilledButton(
              onPressed: () {
                context.read<StoreSettingsBloc>().add(PoliciesSaveRequested(StorePolicies(
                      deductionPoint: _deduction,
                      backorder: _backorder,
                      softHoldTtlSeconds:
                          int.tryParse(_softTtl.text) ?? widget.policies.softHoldTtlSeconds,
                      checkoutHoldTtlSeconds: int.tryParse(_checkoutTtl.text) ??
                          widget.policies.checkoutHoldTtlSeconds,
                    )));
                showInfoSnack(context, 'Policies saved');
              },
              child: const Text('Save policies'),
            ),
          ],
        ),
      ],
    );
  }
}

class _LocationsSection extends StatelessWidget {
  const _LocationsSection({required this.locations});

  final List<StoreLocation> locations;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Locations', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptEdit(context, null),
            icon: const Icon(Icons.add),
            label: const Text('Add location'),
          ),
        ]),
        if (locations.isEmpty)
          const EmptyView('No locations yet.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Label')),
              DataColumn(label: Text('Kind')),
              DataColumn(label: Text('Sellable')),
              DataColumn(label: Text('Address')),
              DataColumn(label: Text('Id')),
              DataColumn(label: Text('')),
            ],
            rows: locations
                .map((l) => DataRow(cells: [
                      DataCell(Text(l.label)),
                      DataCell(StatusChip(l.kind)),
                      DataCell(Icon(l.sellable ? Icons.check : Icons.close, size: 18)),
                      DataCell(Text(
                          l.address == null || l.address!.oneLine.isEmpty
                              ? '—'
                              : l.address!.oneLine)),
                      DataCell(SelectableText(l.id)),
                      DataCell(IconButton(
                        tooltip: 'Edit',
                        icon: const Icon(Icons.edit, size: 18),
                        onPressed: () => _promptEdit(context, l),
                      )),
                    ]))
                .toList(),
          ),
      ],
    );
  }

  Future<void> _promptEdit(BuildContext context, StoreLocation? existing) async {
    final bloc = context.read<StoreSettingsBloc>();
    final label = TextEditingController(text: existing?.label ?? '');
    var kind = existing?.kind ?? StoreLocation.kinds.first;
    var sellable = existing?.sellable ?? true;
    final address = AddressDraft(from: existing?.address?.toJson());
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text(existing == null ? 'Add location' : 'Edit location'),
          content: SizedBox(
            width: 440,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: label,
                    autofocus: true,
                    decoration: const InputDecoration(labelText: 'Label'),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: kind,
                    decoration: const InputDecoration(labelText: 'Kind'),
                    items: StoreLocation.kinds
                        .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                        .toList(),
                    onChanged: (v) => setState(() => kind = v ?? kind),
                  ),
                  SwitchListTile(
                    title: const Text('Sellable'),
                    value: sellable,
                    onChanged: (v) => setState(() => sellable = v),
                  ),
                  const SizedBox(height: 12),
                  AddressFields(draft: address),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: Text(existing == null ? 'Add' : 'Save')),
          ],
        ),
      ),
    );
    if (ok != true || label.text.trim().isEmpty) return;
    final addressJson = address.isEmpty ? null : address.toJson();
    if (existing == null) {
      bloc.add(LocationAddRequested(label.text.trim(), kind, sellable, address: addressJson));
    } else {
      bloc.add(LocationUpdateRequested(
        existing.id,
        label: label.text.trim(),
        kind: kind,
        sellable: sellable,
        address: addressJson,
      ));
    }
  }
}

class _ChannelsSection extends StatelessWidget {
  const _ChannelsSection({required this.channels});

  final List<Channel> channels;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Channels', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptAdd(context),
            icon: const Icon(Icons.add),
            label: const Text('Add channel'),
          ),
        ]),
        if (channels.isEmpty)
          const EmptyView('No channels yet.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Kind')),
              DataColumn(label: Text('Label')),
              DataColumn(label: Text('Active')),
              DataColumn(label: Text('Id')),
            ],
            rows: channels
                .map((c) => DataRow(cells: [
                      DataCell(StatusChip(c.kind)),
                      DataCell(Text(c.label)),
                      DataCell(Icon(c.active ? Icons.check : Icons.close, size: 18)),
                      DataCell(SelectableText(c.id)),
                    ]))
                .toList(),
          ),
      ],
    );
  }

  Future<void> _promptAdd(BuildContext context) async {
    final bloc = context.read<StoreSettingsBloc>();
    final label = TextEditingController();
    var kind = Channel.kinds.first;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Add channel'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: kind,
                decoration: const InputDecoration(labelText: 'Kind'),
                items: Channel.kinds
                    .map((v) => DropdownMenuItem(value: v, child: Text(v)))
                    .toList(),
                onChanged: (v) => setState(() => kind = v ?? kind),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: label,
                decoration: const InputDecoration(labelText: 'Label (optional)'),
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
      bloc.add(ChannelAddRequested(kind, label.text.trim().isEmpty ? null : label.text.trim()));
    }
  }
}

class _RegionsSection extends StatelessWidget {
  const _RegionsSection({required this.regions, required this.defaultCurrency});

  final List<Region> regions;
  final String defaultCurrency;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader('Regions', actions: [
          FilledButton.tonalIcon(
            onPressed: () => _promptEdit(context, null),
            icon: const Icon(Icons.add),
            label: const Text('Add region'),
          ),
        ]),
        if (regions.isEmpty)
          const EmptyView('No regions yet.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Name')),
              DataColumn(label: Text('Currency')),
              DataColumn(label: Text('Tax')),
              DataColumn(label: Text('Tenders')),
              DataColumn(label: Text('')),
            ],
            rows: regions
                .map((r) => DataRow(cells: [
                      DataCell(Text(r.name)),
                      DataCell(Text(r.currency)),
                      DataCell(Text(
                          '${r.tax.label} ${(r.tax.rateBps / 100).toStringAsFixed(2)}%'
                          '${r.tax.inclusive ? ' (incl.)' : ''}')),
                      DataCell(Text(r.tenders.join(', '))),
                      DataCell(IconButton(
                        icon: const Icon(Icons.edit, size: 18),
                        onPressed: () => _promptEdit(context, r),
                      )),
                    ]))
                .toList(),
          ),
      ],
    );
  }

  Future<void> _promptEdit(BuildContext context, Region? existing) async {
    final bloc = context.read<StoreSettingsBloc>();
    final name = TextEditingController(text: existing?.name ?? '');
    final currency = TextEditingController(text: existing?.currency ?? defaultCurrency);
    final taxLabel = TextEditingController(text: existing?.tax.label ?? 'none');
    final rateBps = TextEditingController(text: '${existing?.tax.rateBps ?? 0}');
    var inclusive = existing?.tax.inclusive ?? false;
    final tenders = <String>{...existing?.tenders ?? []};
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text(existing == null ? 'Add region' : 'Edit region'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: name,
                  decoration: const InputDecoration(labelText: 'Name'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: currency,
                  enabled: existing == null,
                  decoration: const InputDecoration(labelText: 'Currency (ISO-4217)'),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: taxLabel,
                        decoration: const InputDecoration(labelText: 'Tax label'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextField(
                        controller: rateBps,
                        keyboardType: TextInputType.number,
                        decoration: const InputDecoration(
                          labelText: 'Rate (bps)',
                          helperText: '1800 = 18%',
                        ),
                      ),
                    ),
                  ],
                ),
                SwitchListTile(
                  title: const Text('Tax inclusive prices'),
                  value: inclusive,
                  onChanged: (v) => setState(() => inclusive = v),
                ),
                const SizedBox(height: 8),
                const Text('Tenders'),
                Wrap(
                  spacing: 8,
                  children: Region.tenderKinds
                      .map((t) => FilterChip(
                            label: Text(t),
                            selected: tenders.contains(t),
                            onSelected: (sel) => setState(
                                () => sel ? tenders.add(t) : tenders.remove(t)),
                          ))
                      .toList(),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: Text(existing == null ? 'Add' : 'Save')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    final tax = TaxPolicy(
      inclusive: inclusive,
      rateBps: int.tryParse(rateBps.text) ?? 0,
      label: taxLabel.text.trim(),
    );
    if (existing == null) {
      bloc.add(RegionAddRequested(
          name.text.trim(), currency.text.trim().toUpperCase(), tax, tenders.toList()));
    } else {
      bloc.add(RegionUpdateRequested(existing.id, name.text.trim(), tax, tenders.toList()));
    }
  }
}
