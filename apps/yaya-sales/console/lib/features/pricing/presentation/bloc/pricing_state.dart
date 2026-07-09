part of 'pricing_bloc.dart';

enum PricingStatus { initial, loading, loaded, failure }

class PricingState extends Equatable {
  const PricingState({
    this.status = PricingStatus.initial,
    this.priceLists = const [],
    this.promotions = const [],
    this.error,
  });

  final PricingStatus status;
  final List<PriceList> priceLists;
  final List<Promotion> promotions;
  final String? error;

  PricingState copyWith({
    PricingStatus? status,
    List<PriceList>? priceLists,
    List<Promotion>? promotions,
    String? error,
  }) =>
      PricingState(
        status: status ?? this.status,
        priceLists: priceLists ?? this.priceLists,
        promotions: promotions ?? this.promotions,
        error: error,
      );

  @override
  List<Object?> get props => [status, priceLists, promotions, error];
}
