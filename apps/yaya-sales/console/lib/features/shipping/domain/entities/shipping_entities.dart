import 'package:equatable/equatable.dart';

import '../../../../core/domain/money.dart';

class RegionMatch extends Equatable {
  const RegionMatch({this.country, this.region, this.postalPrefix});

  final String? country;
  final String? region;
  final String? postalPrefix;

  @override
  List<Object?> get props => [country, region, postalPrefix];
}

class ShippingZone extends Equatable {
  const ShippingZone({required this.id, required this.name, required this.regions});

  final String id;
  final String name;
  final List<RegionMatch> regions;

  @override
  List<Object?> get props => [id, name, regions];
}

class DeliveryMethod extends Equatable {
  const DeliveryMethod({
    required this.id,
    required this.kind,
    required this.label,
    required this.active,
  });

  final String id;
  final String kind; // SHIPPING | LOCAL_DELIVERY | PICKUP | DIGITAL
  final String label;
  final bool active;

  static const kinds = ['SHIPPING', 'LOCAL_DELIVERY', 'PICKUP', 'DIGITAL'];

  @override
  List<Object?> get props => [id, kind, label, active];
}

class Carrier extends Equatable {
  const Carrier({required this.id, required this.kind, required this.name});

  final String id;
  final String kind; // manual | in-house
  final String name;

  @override
  List<Object?> get props => [id, kind, name];
}

/// Authoring shape for a rate rule — mirrors the recursive RuleBody wire shape.
class RateRuleDraft extends Equatable {
  const RateRuleDraft({
    required this.type,
    this.amountMinor,
    this.tiers = const [],
    this.thresholdMinor,
    this.otherwise,
  });

  final String type; // FLAT | BY_WEIGHT | BY_PRICE | FREE_OVER
  final int? amountMinor;
  final List<RateTier> tiers;
  final int? thresholdMinor;
  final RateRuleDraft? otherwise;

  static const types = ['FLAT', 'BY_WEIGHT', 'BY_PRICE', 'FREE_OVER'];

  @override
  List<Object?> get props => [type, amountMinor, tiers, thresholdMinor, otherwise];
}

class RateTier extends Equatable {
  const RateTier({required this.upTo, required this.amountMinor});

  final int upTo;
  final int amountMinor;

  @override
  List<Object?> get props => [upTo, amountMinor];
}

class TransitEstimate extends Equatable {
  const TransitEstimate({required this.minDays, required this.maxDays});

  final int minDays;
  final int maxDays;

  @override
  List<Object?> get props => [minDays, maxDays];
}

class ShippingRate extends Equatable {
  const ShippingRate({
    required this.id,
    required this.zoneId,
    required this.methodId,
    this.carrierId,
    this.serviceLevel,
    required this.ruleSummary,
    this.handlingFee,
    this.transit,
  });

  final String id;
  final String zoneId;
  final String methodId;
  final String? carrierId;
  final String? serviceLevel;

  /// The serialized rule has no type tag — the summary is derived for display.
  final String ruleSummary;
  final Money? handlingFee;
  final TransitEstimate? transit;

  @override
  List<Object?> get props =>
      [id, zoneId, methodId, carrierId, serviceLevel, ruleSummary, handlingFee, transit];
}

class DeliverySlot extends Equatable {
  const DeliverySlot({
    required this.id,
    required this.locationId,
    required this.methodId,
    this.startsAt,
    this.endsAt,
    required this.capacity,
    required this.booked,
  });

  final String id;
  final String locationId;
  final String methodId;
  final DateTime? startsAt;
  final DateTime? endsAt;
  final int capacity;
  final int booked;

  @override
  List<Object?> get props => [id, locationId, methodId, startsAt, endsAt, capacity, booked];
}
