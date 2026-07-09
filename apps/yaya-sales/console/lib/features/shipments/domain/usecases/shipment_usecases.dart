import '../entities/shipment_entities.dart';
import '../repositories/shipment_repository.dart';

class ListShipmentsOfOrder {
  const ListShipmentsOfOrder(this._repo);
  final ShipmentRepository _repo;
  Future<List<Shipment>> call(String orderId) => _repo.ofOrder(orderId);
}

class CreateShipment {
  const CreateShipment(this._repo);
  final ShipmentRepository _repo;
  Future<Shipment> call(String orderId, List<ShipmentLine> lines, String? locationId) =>
      _repo.create(orderId, lines, locationId);
}

class PickShipment {
  const PickShipment(this._repo);
  final ShipmentRepository _repo;
  Future<Shipment> call(String shipmentId) => _repo.pick(shipmentId);
}

class PackShipment {
  const PackShipment(this._repo);
  final ShipmentRepository _repo;
  Future<Shipment> call(String shipmentId, Parcel? parcel) => _repo.pack(shipmentId, parcel);
}

class DispatchShipment {
  const DispatchShipment(this._repo);
  final ShipmentRepository _repo;
  Future<Shipment> call(String shipmentId, String? trackingNumber, String? trackingUrl) =>
      _repo.dispatch(shipmentId, trackingNumber, trackingUrl);
}

class AdvanceDelivery {
  const AdvanceDelivery(this._repo);
  final ShipmentRepository _repo;
  Future<Shipment> call(String shipmentId, String status,
          {Map<String, dynamic>? proof, String? description, String? location}) =>
      _repo.advanceDelivery(shipmentId, status,
          proof: proof, description: description, location: location);
}

class ReadTracking {
  const ReadTracking(this._repo);
  final ShipmentRepository _repo;
  Future<List<TrackingEvent>> call(String shipmentId) => _repo.tracking(shipmentId);
}

class AssignCourier {
  const AssignCourier(this._repo);
  final ShipmentRepository _repo;
  Future<Assignment> call(String shipmentId, String? agentId) =>
      _repo.assign(shipmentId, agentId);
}
