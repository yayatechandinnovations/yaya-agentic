part of 'shipments_bloc.dart';

sealed class ShipmentsEvent extends Equatable {
  const ShipmentsEvent();

  @override
  List<Object?> get props => [];
}

class ShipmentsRequested extends ShipmentsEvent {
  const ShipmentsRequested(this.orderId);

  final String orderId;

  @override
  List<Object?> get props => [orderId];
}

class ShipmentCreateRequested extends ShipmentsEvent {
  const ShipmentCreateRequested({this.lines = const [], this.locationId});

  final List<ShipmentLine> lines;
  final String? locationId;

  @override
  List<Object?> get props => [lines, locationId];
}

class ShipmentPickRequested extends ShipmentsEvent {
  const ShipmentPickRequested(this.shipmentId);

  final String shipmentId;

  @override
  List<Object?> get props => [shipmentId];
}

class ShipmentPackRequested extends ShipmentsEvent {
  const ShipmentPackRequested(this.shipmentId, {this.parcel});

  final String shipmentId;
  final Parcel? parcel;

  @override
  List<Object?> get props => [shipmentId, parcel];
}

class ShipmentDispatchRequested extends ShipmentsEvent {
  const ShipmentDispatchRequested(this.shipmentId, {this.trackingNumber, this.trackingUrl});

  final String shipmentId;
  final String? trackingNumber;
  final String? trackingUrl;

  @override
  List<Object?> get props => [shipmentId, trackingNumber, trackingUrl];
}

class DeliveryAdvanceRequested extends ShipmentsEvent {
  const DeliveryAdvanceRequested(
    this.shipmentId,
    this.deliveryStatus, {
    this.proof,
    this.description,
    this.location,
  });

  final String shipmentId;
  final String deliveryStatus;
  final Map<String, dynamic>? proof;
  final String? description;
  final String? location;

  @override
  List<Object?> get props => [shipmentId, deliveryStatus, proof, description, location];
}

class TrackingRequested extends ShipmentsEvent {
  const TrackingRequested(this.shipmentId);

  final String shipmentId;

  @override
  List<Object?> get props => [shipmentId];
}

class CourierAssignRequested extends ShipmentsEvent {
  const CourierAssignRequested(this.shipmentId, {this.agentId});

  final String shipmentId;
  final String? agentId;

  @override
  List<Object?> get props => [shipmentId, agentId];
}
