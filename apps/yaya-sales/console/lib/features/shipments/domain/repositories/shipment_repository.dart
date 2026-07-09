import '../entities/shipment_entities.dart';

abstract class ShipmentRepository {
  Future<List<Shipment>> ofOrder(String orderId);
  Future<Shipment> create(String orderId, List<ShipmentLine> lines, String? locationId);
  Future<Shipment> pick(String shipmentId);
  Future<Shipment> pack(String shipmentId, Parcel? parcel);
  Future<Shipment> dispatch(String shipmentId, String? trackingNumber, String? trackingUrl);
  Future<Shipment> advanceDelivery(String shipmentId, String status,
      {Map<String, dynamic>? proof, String? description, String? location});
  Future<List<TrackingEvent>> tracking(String shipmentId);

  /// [agentId] null = auto-assign to any approved agent of the store.
  Future<Assignment> assign(String shipmentId, String? agentId);
}
