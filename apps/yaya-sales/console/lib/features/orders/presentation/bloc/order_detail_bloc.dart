import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/order_entities.dart';
import '../../domain/usecases/order_usecases.dart';

part 'order_detail_event.dart';
part 'order_detail_state.dart';

/// One order: detail (lines/totals/delivery/events/tenders) and the operator
/// actions — cancel, add tender, settle tender, refund.
class OrderDetailBloc extends Bloc<OrderDetailEvent, OrderDetailState> {
  OrderDetailBloc({
    required GetOrderDetail getDetail,
    required CancelOrder cancelOrder,
    required AddTender addTender,
    required SettleTender settleTender,
    required RefundOrder refundOrder,
  })  : _getDetail = getDetail,
        _cancelOrder = cancelOrder,
        _addTender = addTender,
        _settleTender = settleTender,
        _refundOrder = refundOrder,
        super(const OrderDetailState()) {
    on<OrderDetailRequested>(_onRequested);
    on<OrderCancelRequested>(_onCancel);
    on<TenderAddRequested>(_onAddTender);
    on<TenderSettleRequested>(_onSettleTender);
    on<OrderRefundRequested>(_onRefund);
  }

  final GetOrderDetail _getDetail;
  final CancelOrder _cancelOrder;
  final AddTender _addTender;
  final SettleTender _settleTender;
  final RefundOrder _refundOrder;

  Future<void> _onRequested(
      OrderDetailRequested event, Emitter<OrderDetailState> emit) async {
    emit(state.copyWith(status: OrderDetailStatus.loading, orderId: event.orderId));
    try {
      final detail = await _getDetail(event.orderId);
      emit(state.copyWith(status: OrderDetailStatus.loaded, detail: detail));
    } on Failure catch (f) {
      emit(state.copyWith(status: OrderDetailStatus.failure, error: f.message));
    }
  }

  Future<void> _onCancel(OrderCancelRequested event, Emitter<OrderDetailState> emit) async {
    await _mutate(emit, () => _cancelOrder(state.orderId));
  }

  Future<void> _onAddTender(TenderAddRequested event, Emitter<OrderDetailState> emit) async {
    await _mutate(emit, () => _addTender(state.orderId, event.kind, event.amountMinor));
  }

  Future<void> _onSettleTender(
      TenderSettleRequested event, Emitter<OrderDetailState> emit) async {
    await _mutate(emit, () async {
      await _settleTender(state.orderId, event.tenderId);
      return true;
    });
  }

  Future<void> _onRefund(OrderRefundRequested event, Emitter<OrderDetailState> emit) async {
    await _mutate(
        emit,
        () => _refundOrder(state.orderId,
            amountMinor: event.amountMinor, toStoreCredit: event.toStoreCredit));
  }

  Future<void> _mutate(
      Emitter<OrderDetailState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(OrderDetailRequested(state.orderId));
    } on Failure catch (f) {
      emit(state.copyWith(status: OrderDetailStatus.failure, error: f.message));
    }
  }
}
