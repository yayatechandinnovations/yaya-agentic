import '../entities/shipping_entities.dart';
import '../repositories/shipping_repository.dart';

class ListZones {
  const ListZones(this._repo);
  final ShippingRepository _repo;
  Future<List<ShippingZone>> call() => _repo.zones();
}

class AddZone {
  const AddZone(this._repo);
  final ShippingRepository _repo;
  Future<ShippingZone> call(String name, List<RegionMatch> regions) =>
      _repo.addZone(name, regions);
}

class ListMethods {
  const ListMethods(this._repo);
  final ShippingRepository _repo;
  Future<List<DeliveryMethod>> call() => _repo.methods();
}

class AddMethod {
  const AddMethod(this._repo);
  final ShippingRepository _repo;
  Future<DeliveryMethod> call(String kind, String label) => _repo.addMethod(kind, label);
}

class SetMethodActive {
  const SetMethodActive(this._repo);
  final ShippingRepository _repo;
  Future<DeliveryMethod> call(String id, bool active) => _repo.setMethodActive(id, active);
}

class ListCarriers {
  const ListCarriers(this._repo);
  final ShippingRepository _repo;
  Future<List<Carrier>> call() => _repo.carriers();
}

class AddCarrier {
  const AddCarrier(this._repo);
  final ShippingRepository _repo;
  Future<Carrier> call(String kind, String name, String? webhookSecret) =>
      _repo.addCarrier(kind, name, webhookSecret);
}

class ListRates {
  const ListRates(this._repo);
  final ShippingRepository _repo;
  Future<List<ShippingRate>> call() => _repo.rates();
}

class AddRate {
  const AddRate(this._repo);
  final ShippingRepository _repo;
  Future<ShippingRate> call({
    required String zoneId,
    required String methodId,
    String? carrierId,
    String? serviceLevel,
    required RateRuleDraft rule,
    int? handlingFeeMinor,
    TransitEstimate? transit,
  }) =>
      _repo.addRate(
        zoneId: zoneId,
        methodId: methodId,
        carrierId: carrierId,
        serviceLevel: serviceLevel,
        rule: rule,
        handlingFeeMinor: handlingFeeMinor,
        transit: transit,
      );
}

class AddSlot {
  const AddSlot(this._repo);
  final ShippingRepository _repo;
  Future<DeliverySlot> call(String locationId, String methodId, DateTime startsAt,
          DateTime endsAt, int capacity) =>
      _repo.addSlot(locationId, methodId, startsAt, endsAt, capacity);
}
