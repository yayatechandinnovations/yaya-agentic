import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../orders/domain/entities/order_entities.dart';
import '../../../orders/domain/usecases/order_usecases.dart';
import '../../domain/entities/rma_entities.dart';
import '../../domain/usecases/rma_usecases.dart';

part 'rmas_event.dart';
part 'rmas_state.dart';

/// The returns desk: list + detail + the full RMA lifecycle. Creating an RMA
/// starts from an order id — the order's lines feed the qty picker.
class RmasBloc extends Bloc<RmasEvent, RmasState> {
  RmasBloc({
    required ListRmas listRmas,
    required GetRma getRma,
    required RequestRma requestRma,
    required AuthorizeRma authorizeRma,
    required RejectRma rejectRma,
    required CancelRma cancelRma,
    required SetReturnShipment setReturnShipment,
    required ReceiveRma receiveRma,
    required GetOrderDetail getOrderDetail,
  })  : _listRmas = listRmas,
        _getRma = getRma,
        _requestRma = requestRma,
        _authorizeRma = authorizeRma,
        _rejectRma = rejectRma,
        _cancelRma = cancelRma,
        _setReturnShipment = setReturnShipment,
        _receiveRma = receiveRma,
        _getOrderDetail = getOrderDetail,
        super(const RmasState()) {
    on<RmasRequested>(_onRequested);
    on<RmaDetailRequested>(_onDetail);
    on<RmaOrderLookupRequested>(_onOrderLookup);
    on<RmaCreateRequested>(_onCreate);
    on<RmaAuthorizeRequested>(_onAuthorize);
    on<RmaRejectRequested>(_onReject);
    on<RmaCancelRequested>(_onCancel);
    on<RmaReturnShipmentSaved>(_onReturnShipment);
    on<RmaReceiveRequested>(_onReceive);
  }

  final ListRmas _listRmas;
  final GetRma _getRma;
  final RequestRma _requestRma;
  final AuthorizeRma _authorizeRma;
  final RejectRma _rejectRma;
  final CancelRma _cancelRma;
  final SetReturnShipment _setReturnShipment;
  final ReceiveRma _receiveRma;
  final GetOrderDetail _getOrderDetail;

  Future<void> _onRequested(RmasRequested event, Emitter<RmasState> emit) async {
    emit(state.copyWith(status: RmasStatus.loading, statusFilter: event.statusFilter));
    try {
      final rmas = await _listRmas(status: event.statusFilter);
      emit(state.copyWith(status: RmasStatus.loaded, rmas: rmas));
    } on Failure catch (f) {
      emit(state.copyWith(status: RmasStatus.failure, error: f.message));
    }
  }

  Future<void> _onDetail(RmaDetailRequested event, Emitter<RmasState> emit) async {
    try {
      final rma = await _getRma(event.rmaId);
      emit(state.copyWith(selected: rma));
    } on Failure catch (f) {
      emit(state.copyWith(status: RmasStatus.failure, error: f.message));
    }
  }

  Future<void> _onOrderLookup(
      RmaOrderLookupRequested event, Emitter<RmasState> emit) async {
    try {
      final detail = await _getOrderDetail(event.orderId);
      emit(state.copyWith(lookupOrder: detail.order));
    } on Failure catch (f) {
      emit(state.copyWith(status: RmasStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(RmaCreateRequested event, Emitter<RmasState> emit) async {
    await _mutate(emit, () => _requestRma(event.orderId, event.lines, event.reason));
  }

  Future<void> _onAuthorize(RmaAuthorizeRequested event, Emitter<RmasState> emit) async {
    await _act(emit, () => _authorizeRma(event.rmaId));
  }

  Future<void> _onReject(RmaRejectRequested event, Emitter<RmasState> emit) async {
    await _act(emit, () => _rejectRma(event.rmaId, event.reason));
  }

  Future<void> _onCancel(RmaCancelRequested event, Emitter<RmasState> emit) async {
    await _act(emit, () => _cancelRma(event.rmaId));
  }

  Future<void> _onReturnShipment(
      RmaReturnShipmentSaved event, Emitter<RmasState> emit) async {
    await _act(emit, () => _setReturnShipment(event.rmaId, event.info));
  }

  Future<void> _onReceive(RmaReceiveRequested event, Emitter<RmasState> emit) async {
    await _act(
        emit,
        () =>
            _receiveRma(event.rmaId, event.dispositions, event.refundTo, event.amountMinor));
  }

  Future<void> _mutate(Emitter<RmasState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(RmasRequested(statusFilter: state.statusFilter));
    } on Failure catch (f) {
      emit(state.copyWith(status: RmasStatus.failure, error: f.message));
    }
  }

  /// An action on the selected RMA: refresh both the detail and the list.
  Future<void> _act(Emitter<RmasState> emit, Future<Rma> Function() action) async {
    try {
      final rma = await action();
      emit(state.copyWith(selected: rma));
      add(RmasRequested(statusFilter: state.statusFilter));
    } on Failure catch (f) {
      emit(state.copyWith(status: RmasStatus.failure, error: f.message));
    }
  }
}
