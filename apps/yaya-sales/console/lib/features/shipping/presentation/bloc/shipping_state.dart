part of 'shipping_bloc.dart';

enum ShippingStatus { initial, loading, loaded, failure }

class ShippingState extends Equatable {
  const ShippingState({
    this.status = ShippingStatus.initial,
    this.zones = const [],
    this.methods = const [],
    this.carriers = const [],
    this.rates = const [],
    this.createdSlots = const [],
    this.error,
  });

  final ShippingStatus status;
  final List<ShippingZone> zones;
  final List<DeliveryMethod> methods;
  final List<Carrier> carriers;
  final List<ShippingRate> rates;
  final List<DeliverySlot> createdSlots;
  final String? error;

  ShippingState copyWith({
    ShippingStatus? status,
    List<ShippingZone>? zones,
    List<DeliveryMethod>? methods,
    List<Carrier>? carriers,
    List<ShippingRate>? rates,
    List<DeliverySlot>? createdSlots,
    String? error,
  }) =>
      ShippingState(
        status: status ?? this.status,
        zones: zones ?? this.zones,
        methods: methods ?? this.methods,
        carriers: carriers ?? this.carriers,
        rates: rates ?? this.rates,
        createdSlots: createdSlots ?? this.createdSlots,
        error: error,
      );

  @override
  List<Object?> get props => [status, zones, methods, carriers, rates, createdSlots, error];
}
