part of 'shipments_bloc.dart';

enum ShipmentsStatus { initial, loading, loaded, failure }

class ShipmentsState extends Equatable {
  const ShipmentsState({
    this.status = ShipmentsStatus.initial,
    this.orderId = '',
    this.shipments = const [],
    this.trackingShipmentId,
    this.tracking = const [],
    this.lastAssignment,
    this.error,
  });

  final ShipmentsStatus status;
  final String orderId;
  final List<Shipment> shipments;
  final String? trackingShipmentId;
  final List<TrackingEvent> tracking;
  final Assignment? lastAssignment;
  final String? error;

  ShipmentsState copyWith({
    ShipmentsStatus? status,
    String? orderId,
    List<Shipment>? shipments,
    String? trackingShipmentId,
    List<TrackingEvent>? tracking,
    Assignment? lastAssignment,
    String? error,
  }) =>
      ShipmentsState(
        status: status ?? this.status,
        orderId: orderId ?? this.orderId,
        shipments: shipments ?? this.shipments,
        trackingShipmentId: trackingShipmentId ?? this.trackingShipmentId,
        tracking: tracking ?? this.tracking,
        lastAssignment: lastAssignment,
        error: error,
      );

  @override
  List<Object?> get props =>
      [status, orderId, shipments, trackingShipmentId, tracking, lastAssignment, error];
}
