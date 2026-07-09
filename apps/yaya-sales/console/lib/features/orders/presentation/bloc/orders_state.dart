part of 'orders_bloc.dart';

enum OrdersStatus { initial, loading, loaded, failure }

class OrdersState extends Equatable {
  const OrdersState({
    this.status = OrdersStatus.initial,
    this.orders = const [],
    this.statusFilter,
    this.error,
  });

  final OrdersStatus status;
  final List<Order> orders;
  final String? statusFilter;
  final String? error;

  OrdersState copyWith({
    OrdersStatus? status,
    List<Order>? orders,
    String? statusFilter,
    String? error,
  }) =>
      OrdersState(
        status: status ?? this.status,
        orders: orders ?? this.orders,
        statusFilter: statusFilter,
        error: error,
      );

  @override
  List<Object?> get props => [status, orders, statusFilter, error];
}
