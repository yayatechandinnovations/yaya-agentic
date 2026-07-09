import 'package:equatable/equatable.dart';

class Supplier extends Equatable {
  const Supplier({required this.id, required this.name, this.contact = const {}});

  final String id;
  final String name;
  final Map<String, dynamic> contact;

  @override
  List<Object?> get props => [id, name, contact];
}

class PoLine extends Equatable {
  const PoLine({
    required this.sku,
    required this.qty,
    required this.unitCostMinor,
    this.receivedQty = 0,
  });

  final String sku;
  final int qty;
  final int unitCostMinor;
  final int receivedQty;

  @override
  List<Object?> get props => [sku, qty, unitCostMinor, receivedQty];
}

class PurchaseOrder extends Equatable {
  const PurchaseOrder({
    required this.id,
    required this.supplierId,
    required this.locationId,
    required this.status,
    this.lines = const [],
    this.expectedAt,
    this.createdAt,
  });

  final String id;
  final String supplierId;
  final String locationId;
  final String status;
  final List<PoLine> lines;
  final DateTime? expectedAt;
  final DateTime? createdAt;

  static const statuses = ['DRAFT', 'PLACED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED'];

  @override
  List<Object?> get props => [id, supplierId, locationId, status, lines, expectedAt, createdAt];
}
