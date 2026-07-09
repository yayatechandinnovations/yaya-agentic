part of 'order_detail_bloc.dart';

enum OrderDetailStatus { initial, loading, loaded, failure }

class OrderDetailState extends Equatable {
  const OrderDetailState({
    this.status = OrderDetailStatus.initial,
    this.orderId = '',
    this.detail,
    this.error,
  });

  final OrderDetailStatus status;
  final String orderId;
  final OrderDetail? detail;
  final String? error;

  OrderDetailState copyWith({
    OrderDetailStatus? status,
    String? orderId,
    OrderDetail? detail,
    String? error,
  }) =>
      OrderDetailState(
        status: status ?? this.status,
        orderId: orderId ?? this.orderId,
        detail: detail ?? this.detail,
        error: error,
      );

  @override
  List<Object?> get props => [status, orderId, detail, error];
}
