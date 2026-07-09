import '../entities/shipping_entities.dart';

abstract class ShippingRepository {
  Future<List<ShippingZone>> zones();
  Future<ShippingZone> addZone(String name, List<RegionMatch> regions);

  Future<List<DeliveryMethod>> methods();
  Future<DeliveryMethod> addMethod(String kind, String label);
  Future<DeliveryMethod> setMethodActive(String id, bool active);

  Future<List<Carrier>> carriers();
  Future<Carrier> addCarrier(String kind, String name, String? webhookSecret);

  Future<List<ShippingRate>> rates();
  Future<ShippingRate> addRate({
    required String zoneId,
    required String methodId,
    String? carrierId,
    String? serviceLevel,
    required RateRuleDraft rule,
    int? handlingFeeMinor,
    TransitEstimate? transit,
  });

  /// The admin API has no slot listing — the created slot (capacity + booked)
  /// is returned so the page can accumulate the session's windows.
  Future<DeliverySlot> addSlot(String locationId, String methodId, DateTime startsAt,
      DateTime endsAt, int capacity);
}
