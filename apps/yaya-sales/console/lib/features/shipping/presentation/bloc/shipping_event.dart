part of 'shipping_bloc.dart';

sealed class ShippingEvent extends Equatable {
  const ShippingEvent();

  @override
  List<Object?> get props => [];
}

class ShippingRequested extends ShippingEvent {
  const ShippingRequested();
}

class ZoneAddRequested extends ShippingEvent {
  const ZoneAddRequested(this.name, this.regions);

  final String name;
  final List<RegionMatch> regions;

  @override
  List<Object?> get props => [name, regions];
}

class MethodAddRequested extends ShippingEvent {
  const MethodAddRequested(this.kind, this.label);

  final String kind;
  final String label;

  @override
  List<Object?> get props => [kind, label];
}

class MethodActiveToggled extends ShippingEvent {
  const MethodActiveToggled(this.id, this.active);

  final String id;
  final bool active;

  @override
  List<Object?> get props => [id, active];
}

class CarrierAddRequested extends ShippingEvent {
  const CarrierAddRequested(this.kind, this.name, this.webhookSecret);

  final String kind;
  final String name;
  final String? webhookSecret;

  @override
  List<Object?> get props => [kind, name, webhookSecret];
}

class RateAddRequested extends ShippingEvent {
  const RateAddRequested({
    required this.zoneId,
    required this.methodId,
    this.carrierId,
    this.serviceLevel,
    required this.rule,
    this.handlingFeeMinor,
    this.transit,
  });

  final String zoneId;
  final String methodId;
  final String? carrierId;
  final String? serviceLevel;
  final RateRuleDraft rule;
  final int? handlingFeeMinor;
  final TransitEstimate? transit;

  @override
  List<Object?> get props =>
      [zoneId, methodId, carrierId, serviceLevel, rule, handlingFeeMinor, transit];
}

class SlotAddRequested extends ShippingEvent {
  const SlotAddRequested({
    required this.locationId,
    required this.methodId,
    required this.startsAt,
    required this.endsAt,
    required this.capacity,
  });

  final String locationId;
  final String methodId;
  final DateTime startsAt;
  final DateTime endsAt;
  final int capacity;

  @override
  List<Object?> get props => [locationId, methodId, startsAt, endsAt, capacity];
}
