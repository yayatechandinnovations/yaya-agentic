import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/shipping_entities.dart';
import '../../domain/repositories/shipping_repository.dart';
import '../datasources/shipping_remote_data_source.dart';
import '../models/shipping_models.dart';

class ShippingRepositoryImpl implements ShippingRepository {
  ShippingRepositoryImpl(this._remote);

  final ShippingRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<ShippingZone>> zones() =>
      _guard(() async => (await _remote.zones()).map(zoneFromJson).toList());

  @override
  Future<ShippingZone> addZone(String name, List<RegionMatch> regions) =>
      _guard(() async => zoneFromJson(await _remote.addZone({
            'name': name,
            'regions': regions
                .map((r) => {
                      if (r.country != null) 'country': r.country,
                      if (r.region != null) 'region': r.region,
                      if (r.postalPrefix != null) 'postalPrefix': r.postalPrefix,
                    })
                .toList(),
          })));

  @override
  Future<List<DeliveryMethod>> methods() =>
      _guard(() async => (await _remote.methods()).map(methodFromJson).toList());

  @override
  Future<DeliveryMethod> addMethod(String kind, String label) =>
      _guard(() async => methodFromJson(await _remote.addMethod(kind, label)));

  @override
  Future<DeliveryMethod> setMethodActive(String id, bool active) =>
      _guard(() async => methodFromJson(await _remote.setMethodActive(id, active)));

  @override
  Future<List<Carrier>> carriers() =>
      _guard(() async => (await _remote.carriers()).map(carrierFromJson).toList());

  @override
  Future<Carrier> addCarrier(String kind, String name, String? webhookSecret) =>
      _guard(() async => carrierFromJson(await _remote.addCarrier(kind, name, webhookSecret)));

  @override
  Future<List<ShippingRate>> rates() =>
      _guard(() async => (await _remote.rates()).map(rateFromJson).toList());

  @override
  Future<ShippingRate> addRate({
    required String zoneId,
    required String methodId,
    String? carrierId,
    String? serviceLevel,
    required RateRuleDraft rule,
    int? handlingFeeMinor,
    TransitEstimate? transit,
  }) =>
      _guard(() async => rateFromJson(await _remote.addRate({
            'zoneId': zoneId,
            'methodId': methodId,
            if (carrierId != null && carrierId.isNotEmpty) 'carrierId': carrierId,
            if (serviceLevel != null && serviceLevel.isNotEmpty)
              'serviceLevel': serviceLevel,
            'rule': ruleDraftToJson(rule),
            if (handlingFeeMinor != null) 'handlingFeeMinor': handlingFeeMinor,
            if (transit != null)
              'transit': {'minDays': transit.minDays, 'maxDays': transit.maxDays},
          })));

  @override
  Future<DeliverySlot> addSlot(String locationId, String methodId, DateTime startsAt,
          DateTime endsAt, int capacity) =>
      _guard(() async => slotFromJson(await _remote.addSlot({
            'locationId': locationId,
            'methodId': methodId,
            'startsAt': startsAt.toUtc().toIso8601String(),
            'endsAt': endsAt.toUtc().toIso8601String(),
            'capacity': capacity,
          })));
}
