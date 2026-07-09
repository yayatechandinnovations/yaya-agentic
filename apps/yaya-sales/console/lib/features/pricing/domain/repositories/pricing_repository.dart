import '../entities/pricing_entities.dart';

abstract class PricingRepository {
  Future<List<PriceList>> priceLists();
  Future<PriceList> createPriceList(
      String? regionId, String? channelId, String? customerGroup, List<PriceRule> rules);
  Future<PriceList> updatePriceList(String id, {List<PriceRule>? rules, bool? active});

  Future<List<Promotion>> promotions();
  Future<Promotion> createPromotion({
    required String kind,
    int? bps,
    int? amountMinor,
    int? minSubtotalMinor,
    List<String> skus,
    String? code,
    DateTime? startsAt,
    DateTime? endsAt,
  });
  Future<Promotion> setPromotionActive(String id, bool active);
}
