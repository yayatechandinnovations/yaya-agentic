import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/shipping_entities.dart';
import '../../domain/usecases/shipping_usecases.dart';

part 'shipping_event.dart';
part 'shipping_state.dart';

/// Shipping configuration: zones, delivery methods, carriers, rates, slots.
/// Slots have no admin listing endpoint — created windows accumulate in state
/// for the session so the operator sees capacity/booked of what they created.
class ShippingBloc extends Bloc<ShippingEvent, ShippingState> {
  ShippingBloc({
    required ListZones listZones,
    required AddZone addZone,
    required ListMethods listMethods,
    required AddMethod addMethod,
    required SetMethodActive setMethodActive,
    required ListCarriers listCarriers,
    required AddCarrier addCarrier,
    required ListRates listRates,
    required AddRate addRate,
    required AddSlot addSlot,
  })  : _listZones = listZones,
        _addZone = addZone,
        _listMethods = listMethods,
        _addMethod = addMethod,
        _setMethodActive = setMethodActive,
        _listCarriers = listCarriers,
        _addCarrier = addCarrier,
        _listRates = listRates,
        _addRate = addRate,
        _addSlot = addSlot,
        super(const ShippingState()) {
    on<ShippingRequested>(_onRequested);
    on<ZoneAddRequested>(_onZoneAdd);
    on<MethodAddRequested>(_onMethodAdd);
    on<MethodActiveToggled>(_onMethodToggle);
    on<CarrierAddRequested>(_onCarrierAdd);
    on<RateAddRequested>(_onRateAdd);
    on<SlotAddRequested>(_onSlotAdd);
  }

  final ListZones _listZones;
  final AddZone _addZone;
  final ListMethods _listMethods;
  final AddMethod _addMethod;
  final SetMethodActive _setMethodActive;
  final ListCarriers _listCarriers;
  final AddCarrier _addCarrier;
  final ListRates _listRates;
  final AddRate _addRate;
  final AddSlot _addSlot;

  Future<void> _onRequested(ShippingRequested event, Emitter<ShippingState> emit) async {
    emit(state.copyWith(status: ShippingStatus.loading));
    try {
      final results = await Future.wait<Object>(
          [_listZones(), _listMethods(), _listCarriers(), _listRates()]);
      emit(state.copyWith(
        status: ShippingStatus.loaded,
        zones: results[0] as List<ShippingZone>,
        methods: results[1] as List<DeliveryMethod>,
        carriers: results[2] as List<Carrier>,
        rates: results[3] as List<ShippingRate>,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: ShippingStatus.failure, error: f.message));
    }
  }

  Future<void> _onZoneAdd(ZoneAddRequested event, Emitter<ShippingState> emit) async {
    await _mutate(emit, () => _addZone(event.name, event.regions));
  }

  Future<void> _onMethodAdd(MethodAddRequested event, Emitter<ShippingState> emit) async {
    await _mutate(emit, () => _addMethod(event.kind, event.label));
  }

  Future<void> _onMethodToggle(MethodActiveToggled event, Emitter<ShippingState> emit) async {
    await _mutate(emit, () => _setMethodActive(event.id, event.active));
  }

  Future<void> _onCarrierAdd(CarrierAddRequested event, Emitter<ShippingState> emit) async {
    await _mutate(emit, () => _addCarrier(event.kind, event.name, event.webhookSecret));
  }

  Future<void> _onRateAdd(RateAddRequested event, Emitter<ShippingState> emit) async {
    await _mutate(
        emit,
        () => _addRate(
              zoneId: event.zoneId,
              methodId: event.methodId,
              carrierId: event.carrierId,
              serviceLevel: event.serviceLevel,
              rule: event.rule,
              handlingFeeMinor: event.handlingFeeMinor,
              transit: event.transit,
            ));
  }

  Future<void> _onSlotAdd(SlotAddRequested event, Emitter<ShippingState> emit) async {
    try {
      final slot = await _addSlot(
          event.locationId, event.methodId, event.startsAt, event.endsAt, event.capacity);
      emit(state.copyWith(createdSlots: [...state.createdSlots, slot]));
    } on Failure catch (f) {
      emit(state.copyWith(status: ShippingStatus.failure, error: f.message));
    }
  }

  Future<void> _mutate(Emitter<ShippingState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(const ShippingRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: ShippingStatus.failure, error: f.message));
    }
  }
}
