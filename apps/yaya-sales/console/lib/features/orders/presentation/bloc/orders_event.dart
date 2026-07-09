part of 'orders_bloc.dart';

sealed class OrdersEvent extends Equatable {
  const OrdersEvent();

  @override
  List<Object?> get props => [];
}

class OrdersRequested extends OrdersEvent {
  const OrdersRequested({this.statusFilter});

  final String? statusFilter;

  @override
  List<Object?> get props => [statusFilter];
}
