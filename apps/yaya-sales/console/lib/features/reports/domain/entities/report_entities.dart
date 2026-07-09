import 'package:equatable/equatable.dart';

import '../../../inventory/domain/entities/inventory_entities.dart';

class SalesReport extends Equatable {
  const SalesReport({
    required this.currency,
    required this.ordersCount,
    required this.grossMinor,
    required this.refundsMinor,
    required this.netMinor,
  });

  final String currency;
  final int ordersCount;
  final int grossMinor;
  final int refundsMinor;
  final int netMinor;

  @override
  List<Object?> get props => [currency, ordersCount, grossMinor, refundsMinor, netMinor];
}

class ValuationLine extends Equatable {
  const ValuationLine({
    required this.sku,
    required this.location,
    required this.onHand,
    required this.avgCostMinor,
    required this.valueMinor,
  });

  final String sku;
  final String location;
  final int onHand;
  final int avgCostMinor;
  final int valueMinor;

  @override
  List<Object?> get props => [sku, location, onHand, avgCostMinor, valueMinor];
}

class ValuationReport extends Equatable {
  const ValuationReport({
    required this.currency,
    required this.totalMinor,
    required this.lines,
  });

  final String currency;
  final int totalMinor;
  final List<ValuationLine> lines;

  @override
  List<Object?> get props => [currency, totalMinor, lines];
}

class MarginReport extends Equatable {
  const MarginReport({
    required this.currency,
    required this.revenueMinor,
    required this.cogsMinor,
    required this.marginMinor,
  });

  final String currency;
  final int revenueMinor;
  final int cogsMinor;
  final int marginMinor;

  @override
  List<Object?> get props => [currency, revenueMinor, cogsMinor, marginMinor];
}

class CourierCod extends Equatable {
  const CourierCod({
    required this.agentId,
    required this.legalName,
    required this.ordersDelivered,
    required this.codSettledMinor,
  });

  final String agentId;
  final String legalName;
  final int ordersDelivered;
  final int codSettledMinor;

  @override
  List<Object?> get props => [agentId, legalName, ordersDelivered, codSettledMinor];
}

class CodMismatch extends Equatable {
  const CodMismatch({required this.orderId, required this.amountMinor});

  final String orderId;
  final int amountMinor;

  @override
  List<Object?> get props => [orderId, amountMinor];
}

class CodReport extends Equatable {
  const CodReport({
    required this.currency,
    required this.couriers,
    required this.settledWithoutDelivery,
    required this.deliveredWithoutSettlement,
  });

  final String currency;
  final List<CourierCod> couriers;
  final List<CodMismatch> settledWithoutDelivery;
  final List<CodMismatch> deliveredWithoutSettlement;

  @override
  List<Object?> get props =>
      [currency, couriers, settledWithoutDelivery, deliveredWithoutSettlement];
}

class LowStockReport extends Equatable {
  const LowStockReport({required this.levels});

  final List<StockLevel> levels;

  @override
  List<Object?> get props => [levels];
}
