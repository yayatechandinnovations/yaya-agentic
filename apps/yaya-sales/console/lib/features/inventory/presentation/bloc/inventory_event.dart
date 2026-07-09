part of 'inventory_bloc.dart';

sealed class InventoryEvent extends Equatable {
  const InventoryEvent();

  @override
  List<Object?> get props => [];
}

class LevelsRequested extends InventoryEvent {
  const LevelsRequested({this.location, this.lowOnly = false});

  final String? location;
  final bool lowOnly;

  @override
  List<Object?> get props => [location, lowOnly];
}

class LedgerRequested extends InventoryEvent {
  const LedgerRequested(this.sku, {this.location});

  final String sku;
  final String? location;

  @override
  List<Object?> get props => [sku, location];
}

class LedgerClosed extends InventoryEvent {
  const LedgerClosed();
}

class ReceiveRequested extends InventoryEvent {
  const ReceiveRequested({
    required this.sku,
    required this.location,
    required this.qty,
    required this.unitCostMinor,
    required this.currency,
    this.poRef,
  });

  final String sku;
  final String location;
  final int qty;
  final int unitCostMinor;
  final String currency;
  final String? poRef;

  @override
  List<Object?> get props => [sku, location, qty, unitCostMinor, currency, poRef];
}

class AdjustRequested extends InventoryEvent {
  const AdjustRequested({
    required this.sku,
    required this.location,
    required this.delta,
    required this.reason,
  });

  final String sku;
  final String location;
  final int delta;
  final String reason;

  @override
  List<Object?> get props => [sku, location, delta, reason];
}

class TransferRequested extends InventoryEvent {
  const TransferRequested({
    required this.sku,
    required this.from,
    required this.to,
    required this.qty,
  });

  final String sku;
  final String from;
  final String to;
  final int qty;

  @override
  List<Object?> get props => [sku, from, to, qty];
}

class ReorderPolicyRequested extends InventoryEvent {
  const ReorderPolicyRequested({
    required this.sku,
    required this.location,
    required this.point,
    required this.qty,
  });

  final String sku;
  final String location;
  final int point;
  final int qty;

  @override
  List<Object?> get props => [sku, location, point, qty];
}

class BackorderPolicyRequested extends InventoryEvent {
  const BackorderPolicyRequested({
    required this.sku,
    required this.location,
    required this.policy,
  });

  final String sku;
  final String location;
  final String policy;

  @override
  List<Object?> get props => [sku, location, policy];
}
