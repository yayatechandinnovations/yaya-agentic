import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/domain/money.dart';
import '../../../../core/widgets/widgets.dart';
import '../bloc/reports_bloc.dart';

/// Reports: sales / valuation / margin / low stock / COD reconciliation.
/// Simple tables + totals; every money figure comes from server minor units.
class ReportsPage extends StatefulWidget {
  const ReportsPage({super.key});

  @override
  State<ReportsPage> createState() => _ReportsPageState();
}

class _ReportsPageState extends State<ReportsPage> with TickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 5, vsync: this);
  final _from = TextEditingController();
  final _to = TextEditingController();

  @override
  void initState() {
    super.initState();
    _tabs.addListener(() {
      if (_tabs.indexIsChanging) return;
      _loadCurrent();
    });
  }

  @override
  void dispose() {
    _tabs.dispose();
    _from.dispose();
    _to.dispose();
    super.dispose();
  }

  DateTime? get _fromDate => DateTime.tryParse(_from.text.trim());
  DateTime? get _toDate => DateTime.tryParse(_to.text.trim());

  void _loadCurrent() {
    final bloc = context.read<ReportsBloc>();
    switch (_tabs.index) {
      case 0:
        bloc.add(SalesReportRequested(from: _fromDate, to: _toDate));
      case 1:
        bloc.add(const ValuationReportRequested());
      case 2:
        bloc.add(MarginReportRequested(from: _fromDate, to: _toDate));
      case 3:
        bloc.add(const LowStockReportRequested());
      case 4:
        bloc.add(CodReportRequested(from: _fromDate, to: _toDate));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        TabBar(
          controller: _tabs,
          tabs: const [
            Tab(text: 'Sales'),
            Tab(text: 'Valuation'),
            Tab(text: 'Margin'),
            Tab(text: 'Low stock'),
            Tab(text: 'COD'),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(
            children: [
              SizedBox(
                width: 240,
                child: TextField(
                  controller: _from,
                  decoration: const InputDecoration(
                    labelText: 'From (ISO-8601, optional)',
                    isDense: true,
                    hintText: '2026-07-01T00:00:00Z',
                  ),
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                width: 240,
                child: TextField(
                  controller: _to,
                  decoration: const InputDecoration(
                    labelText: 'To (ISO-8601, optional)',
                    isDense: true,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              FilledButton.tonal(onPressed: _loadCurrent, child: const Text('Run')),
            ],
          ),
        ),
        Expanded(
          child: BlocConsumer<ReportsBloc, ReportsState>(
            listenWhen: (prev, next) =>
                next.status == ReportsStatus.failure && next.error != null,
            listener: (context, state) => showErrorSnack(context, state.error!),
            builder: (context, state) {
              if (state.status == ReportsStatus.loading) return const LoadingView();
              return TabBarView(
                controller: _tabs,
                children: [
                  _sales(state),
                  _valuation(state),
                  _margin(state),
                  _lowStock(state),
                  _cod(state),
                ],
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _sales(ReportsState state) {
    final report = state.sales;
    if (report == null) return const EmptyView('Run the sales report.');
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        KeyValueRow('Orders', '${report.ordersCount}'),
        KeyValueRow('Gross', formatMinor(report.grossMinor, report.currency)),
        KeyValueRow('Refunds', formatMinor(report.refundsMinor, report.currency)),
        const Divider(),
        KeyValueRow('Net', formatMinor(report.netMinor, report.currency)),
      ],
    );
  }

  Widget _valuation(ReportsState state) {
    final report = state.valuation;
    if (report == null) return const EmptyView('Run the valuation report.');
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        KeyValueRow('Total value', formatMinor(report.totalMinor, report.currency)),
        const SizedBox(height: 12),
        DataTable(
          columns: const [
            DataColumn(label: Text('SKU')),
            DataColumn(label: Text('Location')),
            DataColumn(label: Text('On hand'), numeric: true),
            DataColumn(label: Text('Avg cost')),
            DataColumn(label: Text('Value')),
          ],
          rows: report.lines
              .map((l) => DataRow(cells: [
                    DataCell(Text(l.sku)),
                    DataCell(Text(l.location)),
                    DataCell(Text('${l.onHand}')),
                    DataCell(Text(formatMinor(l.avgCostMinor, report.currency))),
                    DataCell(Text(formatMinor(l.valueMinor, report.currency))),
                  ]))
              .toList(),
        ),
      ],
    );
  }

  Widget _margin(ReportsState state) {
    final report = state.margin;
    if (report == null) return const EmptyView('Run the margin report.');
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        KeyValueRow('Revenue', formatMinor(report.revenueMinor, report.currency)),
        KeyValueRow('COGS', formatMinor(report.cogsMinor, report.currency)),
        const Divider(),
        KeyValueRow('Margin', formatMinor(report.marginMinor, report.currency)),
      ],
    );
  }

  Widget _lowStock(ReportsState state) {
    final report = state.lowStock;
    if (report == null) return const EmptyView('Run the low-stock report.');
    if (report.levels.isEmpty) return const EmptyView('Nothing is low on stock.');
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: DataTable(
        columns: const [
          DataColumn(label: Text('SKU')),
          DataColumn(label: Text('Location')),
          DataColumn(label: Text('Available'), numeric: true),
          DataColumn(label: Text('Reorder point'), numeric: true),
          DataColumn(label: Text('Reorder qty'), numeric: true),
        ],
        rows: report.levels
            .map((l) => DataRow(cells: [
                  DataCell(Text(l.sku)),
                  DataCell(Text(l.locationId)),
                  DataCell(Text('${l.available}')),
                  DataCell(Text('${l.reorderPoint}')),
                  DataCell(Text('${l.reorderQty}')),
                ]))
            .toList(),
      ),
    );
  }

  Widget _cod(ReportsState state) {
    final report = state.cod;
    if (report == null) return const EmptyView('Run the COD reconciliation report.');
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const SectionHeader('Per courier'),
        if (report.couriers.isEmpty)
          const EmptyView('No COD activity in this window.')
        else
          DataTable(
            columns: const [
              DataColumn(label: Text('Courier')),
              DataColumn(label: Text('Orders delivered'), numeric: true),
              DataColumn(label: Text('COD settled')),
            ],
            rows: report.couriers
                .map((c) => DataRow(cells: [
                      DataCell(Text('${c.legalName} (${c.agentId})')),
                      DataCell(Text('${c.ordersDelivered}')),
                      DataCell(Text(formatMinor(c.codSettledMinor, report.currency))),
                    ]))
                .toList(),
          ),
        const Divider(height: 32),
        const SectionHeader('Mismatches — settled without delivery'),
        if (report.settledWithoutDelivery.isEmpty)
          const EmptyView('None.')
        else
          ...report.settledWithoutDelivery.map((m) => ListTile(
                dense: true,
                leading: const Icon(Icons.warning, color: Colors.orange),
                title: Text(m.orderId),
                trailing: Text(formatMinor(m.amountMinor, report.currency)),
              )),
        const Divider(height: 32),
        const SectionHeader('Mismatches — delivered without settlement'),
        if (report.deliveredWithoutSettlement.isEmpty)
          const EmptyView('None.')
        else
          ...report.deliveredWithoutSettlement.map((m) => ListTile(
                dense: true,
                leading: const Icon(Icons.warning, color: Colors.red),
                title: Text(m.orderId),
                trailing: Text(formatMinor(m.amountMinor, report.currency)),
              )),
      ],
    );
  }
}
