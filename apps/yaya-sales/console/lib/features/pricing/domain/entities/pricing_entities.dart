import 'package:equatable/equatable.dart';

/// One price-list rule: sku (+ optional min qty tier) → price in minor units.
class PriceRule extends Equatable {
  const PriceRule({required this.sku, this.minQty, required this.priceMinor});

  final String sku;
  final int? minQty;
  final int priceMinor;

  @override
  List<Object?> get props => [sku, minQty, priceMinor];
}

class PriceList extends Equatable {
  const PriceList({
    required this.id,
    this.regionId,
    this.channelId,
    this.customerGroup,
    required this.rules,
    required this.active,
  });

  final String id;
  final String? regionId;
  final String? channelId;
  final String? customerGroup;
  final List<PriceRule> rules;
  final bool active;

  @override
  List<Object?> get props => [id, regionId, channelId, customerGroup, rules, active];
}

class Promotion extends Equatable {
  const Promotion({
    required this.id,
    required this.kind,
    this.bps,
    this.amountMinor,
    this.minSubtotalMinor,
    this.skus = const [],
    this.code,
    required this.active,
  });

  final String id;
  final String kind; // PERCENT_OFF | AMOUNT_OFF | …
  final int? bps;
  final int? amountMinor;
  final int? minSubtotalMinor;
  final List<String> skus;
  final String? code;
  final bool active;

  static const kinds = ['PERCENT_OFF', 'AMOUNT_OFF'];

  @override
  List<Object?> get props => [id, kind, bps, amountMinor, minSubtotalMinor, skus, code, active];
}
