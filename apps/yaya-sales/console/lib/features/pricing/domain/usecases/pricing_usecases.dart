import '../entities/pricing_entities.dart';
import '../repositories/pricing_repository.dart';

class ListPriceLists {
  const ListPriceLists(this._repo);
  final PricingRepository _repo;
  Future<List<PriceList>> call() => _repo.priceLists();
}

class CreatePriceList {
  const CreatePriceList(this._repo);
  final PricingRepository _repo;
  Future<PriceList> call(String? regionId, String? channelId, String? customerGroup,
          List<PriceRule> rules) =>
      _repo.createPriceList(regionId, channelId, customerGroup, rules);
}

class UpdatePriceList {
  const UpdatePriceList(this._repo);
  final PricingRepository _repo;
  Future<PriceList> call(String id, {List<PriceRule>? rules, bool? active}) =>
      _repo.updatePriceList(id, rules: rules, active: active);
}

class ListPromotions {
  const ListPromotions(this._repo);
  final PricingRepository _repo;
  Future<List<Promotion>> call() => _repo.promotions();
}

class CreatePromotion {
  const CreatePromotion(this._repo);
  final PricingRepository _repo;
  Future<Promotion> call({
    required String kind,
    int? bps,
    int? amountMinor,
    int? minSubtotalMinor,
    List<String> skus = const [],
    String? code,
    DateTime? startsAt,
    DateTime? endsAt,
  }) =>
      _repo.createPromotion(
        kind: kind,
        bps: bps,
        amountMinor: amountMinor,
        minSubtotalMinor: minSubtotalMinor,
        skus: skus,
        code: code,
        startsAt: startsAt,
        endsAt: endsAt,
      );
}

class TogglePromotion {
  const TogglePromotion(this._repo);
  final PricingRepository _repo;
  Future<Promotion> call(String id, bool active) => _repo.setPromotionActive(id, active);
}
