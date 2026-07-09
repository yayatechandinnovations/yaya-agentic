import 'package:equatable/equatable.dart';

import '../../../../core/domain/money.dart';

class StockLevel extends Equatable {
  const StockLevel({
    required this.sku,
    required this.locationId,
    required this.onHand,
    required this.reserved,
    required this.reorderPoint,
    required this.reorderQty,
    required this.backorder,
    this.avgCost,
  });

  final String sku;
  final String locationId;
  final int onHand;
  final int reserved;
  final int reorderPoint;
  final int reorderQty;
  final String backorder; // DENY | ALLOW | PREORDER
  final Money? avgCost;

  int get available => onHand - reserved;
  bool get low => reorderPoint > 0 && available <= reorderPoint;

  @override
  List<Object?> get props =>
      [sku, locationId, onHand, reserved, reorderPoint, reorderQty, backorder, avgCost];
}

class Movement extends Equatable {
  const Movement({
    required this.id,
    required this.sku,
    required this.locationId,
    required this.qtyDelta,
    required this.kind,
    this.unitCost,
    this.refType,
    this.refId,
    this.reason,
    this.occurredAt,
  });

  final String id;
  final String sku;
  final String locationId;
  final int qtyDelta;
  final String kind;
  final Money? unitCost;
  final String? refType;
  final String? refId;
  final String? reason;
  final DateTime? occurredAt;

  @override
  List<Object?> get props =>
      [id, sku, locationId, qtyDelta, kind, unitCost, refType, refId, reason, occurredAt];
}
