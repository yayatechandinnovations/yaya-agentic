part of 'pricing_bloc.dart';

sealed class PricingEvent extends Equatable {
  const PricingEvent();

  @override
  List<Object?> get props => [];
}

class PricingRequested extends PricingEvent {
  const PricingRequested();
}

class PriceListCreateRequested extends PricingEvent {
  const PriceListCreateRequested({
    this.regionId,
    this.channelId,
    this.customerGroup,
    required this.rules,
  });

  final String? regionId;
  final String? channelId;
  final String? customerGroup;
  final List<PriceRule> rules;

  @override
  List<Object?> get props => [regionId, channelId, customerGroup, rules];
}

class PriceListUpdateRequested extends PricingEvent {
  const PriceListUpdateRequested(this.id, {this.rules, this.active});

  final String id;
  final List<PriceRule>? rules;
  final bool? active;

  @override
  List<Object?> get props => [id, rules, active];
}

class PromotionCreateRequested extends PricingEvent {
  const PromotionCreateRequested({
    required this.kind,
    this.bps,
    this.amountMinor,
    this.minSubtotalMinor,
    this.skus = const [],
    this.code,
    this.startsAt,
    this.endsAt,
  });

  final String kind;
  final int? bps;
  final int? amountMinor;
  final int? minSubtotalMinor;
  final List<String> skus;
  final String? code;
  final DateTime? startsAt;
  final DateTime? endsAt;

  @override
  List<Object?> get props =>
      [kind, bps, amountMinor, minSubtotalMinor, skus, code, startsAt, endsAt];
}

class PromotionToggleRequested extends PricingEvent {
  const PromotionToggleRequested(this.id, this.active);

  final String id;
  final bool active;

  @override
  List<Object?> get props => [id, active];
}
