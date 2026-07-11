part of 'pricing_bloc.dart';

enum PricingStatus { initial, loading, loaded, failure }

class PricingState extends Equatable {
  const PricingState({
    this.status = PricingStatus.initial,
    this.priceLists = const [],
    this.promotions = const [],
    this.regions = const [],
    this.channels = const [],
    this.skuOptions = const [],
    this.error,
  });

  final PricingStatus status;
  final List<PriceList> priceLists;
  final List<Promotion> promotions;
  final List<Region> regions;
  final List<Channel> channels;
  final List<Variant> skuOptions;
  final String? error;

  PricingState copyWith({
    PricingStatus? status,
    List<PriceList>? priceLists,
    List<Promotion>? promotions,
    List<Region>? regions,
    List<Channel>? channels,
    List<Variant>? skuOptions,
    String? error,
  }) =>
      PricingState(
        status: status ?? this.status,
        priceLists: priceLists ?? this.priceLists,
        promotions: promotions ?? this.promotions,
        regions: regions ?? this.regions,
        channels: channels ?? this.channels,
        skuOptions: skuOptions ?? this.skuOptions,
        error: error,
      );

  @override
  List<Object?> get props =>
      [status, priceLists, promotions, regions, channels, skuOptions, error];
}
