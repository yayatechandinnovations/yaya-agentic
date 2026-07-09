import 'package:equatable/equatable.dart';

class RmaLine extends Equatable {
  const RmaLine({required this.sku, required this.qty});

  final String sku;
  final int qty;

  @override
  List<Object?> get props => [sku, qty];
}

class RmaDisposition extends Equatable {
  const RmaDisposition({required this.sku, required this.qty, required this.kind});

  final String sku;
  final int qty;
  final String kind; // RESTOCK | SHRINKAGE | DAMAGE

  static const kinds = ['RESTOCK', 'SHRINKAGE', 'DAMAGE'];

  @override
  List<Object?> get props => [sku, qty, kind];
}

class ReturnShipmentInfo extends Equatable {
  const ReturnShipmentInfo({this.carrier, this.trackingNumber, this.instructions});

  final String? carrier;
  final String? trackingNumber;
  final String? instructions;

  @override
  List<Object?> get props => [carrier, trackingNumber, instructions];
}

class Rma extends Equatable {
  const Rma({
    required this.id,
    required this.orderId,
    required this.status,
    this.reason,
    this.lines = const [],
    this.returnShipment,
    this.dispositions = const [],
    this.refundRef,
    this.receivedAt,
    this.createdAt,
  });

  final String id;
  final String orderId;
  final String status;
  final String? reason;
  final List<RmaLine> lines;
  final ReturnShipmentInfo? returnShipment;
  final List<RmaDisposition> dispositions;
  final String? refundRef;
  final DateTime? receivedAt;
  final DateTime? createdAt;

  static const statuses =
      ['REQUESTED', 'AUTHORIZED', 'RECEIVED', 'CLOSED', 'REJECTED', 'CANCELLED'];

  @override
  List<Object?> get props => [
        id,
        orderId,
        status,
        reason,
        lines,
        returnShipment,
        dispositions,
        refundRef,
        receivedAt,
        createdAt,
      ];
}
