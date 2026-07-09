import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/pricing_entities.dart';
import '../../domain/repositories/pricing_repository.dart';
import '../datasources/pricing_remote_data_source.dart';
import '../models/pricing_models.dart';

class PricingRepositoryImpl implements PricingRepository {
  PricingRepositoryImpl(this._remote);

  final PricingRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<PriceList>> priceLists() =>
      _guard(() async => (await _remote.priceLists()).map(priceListFromJson).toList());

  @override
  Future<PriceList> createPriceList(String? regionId, String? channelId,
          String? customerGroup, List<PriceRule> rules) =>
      _guard(() async => priceListFromJson(await _remote.createPriceList({
            if (regionId != null && regionId.isNotEmpty) 'regionId': regionId,
            if (channelId != null && channelId.isNotEmpty) 'channelId': channelId,
            if (customerGroup != null && customerGroup.isNotEmpty)
              'customerGroup': customerGroup,
            'rules': rules.map(ruleToJson).toList(),
          })));

  @override
  Future<PriceList> updatePriceList(String id, {List<PriceRule>? rules, bool? active}) =>
      _guard(() async => priceListFromJson(await _remote.updatePriceList(id, {
            if (rules != null) 'rules': rules.map(ruleToJson).toList(),
            if (active != null) 'active': active,
          })));

  @override
  Future<List<Promotion>> promotions() =>
      _guard(() async => (await _remote.promotions()).map(promotionFromJson).toList());

  @override
  Future<Promotion> createPromotion({
    required String kind,
    int? bps,
    int? amountMinor,
    int? minSubtotalMinor,
    List<String> skus = const [],
    String? code,
    DateTime? startsAt,
    DateTime? endsAt,
  }) =>
      _guard(() async => promotionFromJson(await _remote.createPromotion({
            'kind': kind,
            'value': {
              if (bps != null) 'bps': bps,
              if (amountMinor != null) 'amountMinor': amountMinor,
            },
            if (minSubtotalMinor != null || skus.isNotEmpty)
              'eligibility': {
                if (minSubtotalMinor != null) 'minSubtotalMinor': minSubtotalMinor,
                if (skus.isNotEmpty) 'skus': skus,
              },
            if (code != null && code.isNotEmpty) 'code': code,
            if (startsAt != null) 'startsAt': startsAt.toUtc().toIso8601String(),
            if (endsAt != null) 'endsAt': endsAt.toUtc().toIso8601String(),
          })));

  @override
  Future<Promotion> setPromotionActive(String id, bool active) => _guard(
      () async => promotionFromJson(await _remote.updatePromotion(id, {'active': active})));
}
