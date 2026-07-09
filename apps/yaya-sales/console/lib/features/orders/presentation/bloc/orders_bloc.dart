import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/order_entities.dart';
import '../../domain/usecases/order_usecases.dart';

part 'orders_event.dart';
part 'orders_state.dart';

/// Orders list with a status filter.
class OrdersBloc extends Bloc<OrdersEvent, OrdersState> {
  OrdersBloc({required ListOrders listOrders})
      : _listOrders = listOrders,
        super(const OrdersState()) {
    on<OrdersRequested>(_onRequested);
  }

  final ListOrders _listOrders;

  Future<void> _onRequested(OrdersRequested event, Emitter<OrdersState> emit) async {
    emit(state.copyWith(status: OrdersStatus.loading, statusFilter: event.statusFilter));
    try {
      final orders = await _listOrders(status: event.statusFilter);
      emit(state.copyWith(status: OrdersStatus.loaded, orders: orders));
    } on Failure catch (f) {
      emit(state.copyWith(status: OrdersStatus.failure, error: f.message));
    }
  }
}
