import 'package:equatable/equatable.dart';

class ShipmentLine extends Equatable {
  const ShipmentLine({required this.sku, required this.qty});

  final String sku;
  final int qty;

  @override
  List<Object?> get props => [sku, qty];
}

class Parcel extends Equatable {
  const Parcel({this.weightGrams, this.lengthCm, this.widthCm, this.heightCm});

  final int? weightGrams;
  final int? lengthCm;
  final int? widthCm;
  final int? heightCm;

  @override
  List<Object?> get props => [weightGrams, lengthCm, widthCm, heightCm];
}

class Shipment extends Equatable {
  const Shipment({
    required this.id,
    required this.orderId,
    required this.status,
    this.deliveryStatus,
    this.kind,
    this.locationId,
    this.carrierId,
    this.lines = const [],
    this.parcel,
    this.trackingNumber,
    this.trackingUrl,
    this.dispatchedAt,
    this.deliveredAt,
  });

  final String id;
  final String orderId;
  final String status; // DRAFT | PICKING | PACKED | DISPATCHED
  final String? deliveryStatus;
  final String? kind;
  final String? locationId;
  final String? carrierId;
  final List<ShipmentLine> lines;
  final Parcel? parcel;
  final String? trackingNumber;
  final String? trackingUrl;
  final DateTime? dispatchedAt;
  final DateTime? deliveredAt;

  static const deliveryStatuses = [
    'LABEL_CREATED',
    'READY_FOR_PICKUP',
    'IN_TRANSIT',
    'OUT_FOR_DELIVERY',
    'DELIVERED',
    'PICKED_UP',
    'FAILED',
    'RETURNED_TO_SENDER',
  ];

  @override
  List<Object?> get props => [
        id,
        orderId,
        status,
        deliveryStatus,
        kind,
        locationId,
        carrierId,
        lines,
        parcel,
        trackingNumber,
        trackingUrl,
        dispatchedAt,
        deliveredAt,
      ];
}

class TrackingEvent extends Equatable {
  const TrackingEvent({
    required this.status,
    this.description,
    this.location,
    this.occurredAt,
  });

  final String status;
  final String? description;
  final String? location;
  final DateTime? occurredAt;

  @override
  List<Object?> get props => [status, description, location, occurredAt];
}

class Assignment extends Equatable {
  const Assignment({
    required this.id,
    required this.shipmentId,
    required this.agentId,
    required this.status,
  });

  final String id;
  final String shipmentId;
  final String agentId;
  final String status;

  @override
  List<Object?> get props => [id, shipmentId, agentId, status];
}
