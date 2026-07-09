import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/shipment_entities.dart';
import '../../domain/usecases/shipment_usecases.dart';

part 'shipments_event.dart';
part 'shipments_state.dart';

/// Shipments of one order: create, pick→pack→dispatch, manual delivery
/// advance, courier assignment, and the tracking timeline.
class ShipmentsBloc extends Bloc<ShipmentsEvent, ShipmentsState> {
  ShipmentsBloc({
    required ListShipmentsOfOrder listShipments,
    required CreateShipment createShipment,
    required PickShipment pickShipment,
    required PackShipment packShipment,
    required DispatchShipment dispatchShipment,
    required AdvanceDelivery advanceDelivery,
    required ReadTracking readTracking,
    required AssignCourier assignCourier,
  })  : _listShipments = listShipments,
        _createShipment = createShipment,
        _pickShipment = pickShipment,
        _packShipment = packShipment,
        _dispatchShipment = dispatchShipment,
        _advanceDelivery = advanceDelivery,
        _readTracking = readTracking,
        _assignCourier = assignCourier,
        super(const ShipmentsState()) {
    on<ShipmentsRequested>(_onRequested);
    on<ShipmentCreateRequested>(_onCreate);
    on<ShipmentPickRequested>(_onPick);
    on<ShipmentPackRequested>(_onPack);
    on<ShipmentDispatchRequested>(_onDispatch);
    on<DeliveryAdvanceRequested>(_onAdvance);
    on<TrackingRequested>(_onTracking);
    on<CourierAssignRequested>(_onAssign);
  }

  final ListShipmentsOfOrder _listShipments;
  final CreateShipment _createShipment;
  final PickShipment _pickShipment;
  final PackShipment _packShipment;
  final DispatchShipment _dispatchShipment;
  final AdvanceDelivery _advanceDelivery;
  final ReadTracking _readTracking;
  final AssignCourier _assignCourier;

  Future<void> _onRequested(ShipmentsRequested event, Emitter<ShipmentsState> emit) async {
    emit(state.copyWith(status: ShipmentsStatus.loading, orderId: event.orderId));
    try {
      final shipments = await _listShipments(event.orderId);
      emit(state.copyWith(status: ShipmentsStatus.loaded, shipments: shipments));
    } on Failure catch (f) {
      emit(state.copyWith(status: ShipmentsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(ShipmentCreateRequested event, Emitter<ShipmentsState> emit) async {
    await _mutate(emit, () => _createShipment(state.orderId, event.lines, event.locationId));
  }

  Future<void> _onPick(ShipmentPickRequested event, Emitter<ShipmentsState> emit) async {
    await _mutate(emit, () => _pickShipment(event.shipmentId));
  }

  Future<void> _onPack(ShipmentPackRequested event, Emitter<ShipmentsState> emit) async {
    await _mutate(emit, () => _packShipment(event.shipmentId, event.parcel));
  }

  Future<void> _onDispatch(
      ShipmentDispatchRequested event, Emitter<ShipmentsState> emit) async {
    await _mutate(
        emit, () => _dispatchShipment(event.shipmentId, event.trackingNumber, event.trackingUrl));
  }

  Future<void> _onAdvance(
      DeliveryAdvanceRequested event, Emitter<ShipmentsState> emit) async {
    await _mutate(
        emit,
        () => _advanceDelivery(event.shipmentId, event.deliveryStatus,
            proof: event.proof, description: event.description, location: event.location));
  }

  Future<void> _onTracking(TrackingRequested event, Emitter<ShipmentsState> emit) async {
    try {
      final timeline = await _readTracking(event.shipmentId);
      emit(state.copyWith(trackingShipmentId: event.shipmentId, tracking: timeline));
    } on Failure catch (f) {
      emit(state.copyWith(status: ShipmentsStatus.failure, error: f.message));
    }
  }

  Future<void> _onAssign(
      CourierAssignRequested event, Emitter<ShipmentsState> emit) async {
    try {
      final assignment = await _assignCourier(event.shipmentId, event.agentId);
      emit(state.copyWith(lastAssignment: assignment));
      add(ShipmentsRequested(state.orderId));
    } on Failure catch (f) {
      emit(state.copyWith(status: ShipmentsStatus.failure, error: f.message));
    }
  }

  Future<void> _mutate(Emitter<ShipmentsState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(ShipmentsRequested(state.orderId));
    } on Failure catch (f) {
      emit(state.copyWith(status: ShipmentsStatus.failure, error: f.message));
    }
  }
}
