part of 'order_detail_bloc.dart';

sealed class OrderDetailEvent extends Equatable {
  const OrderDetailEvent();

  @override
  List<Object?> get props => [];
}

class OrderDetailRequested extends OrderDetailEvent {
  const OrderDetailRequested(this.orderId);

  final String orderId;

  @override
  List<Object?> get props => [orderId];
}

class OrderCancelRequested extends OrderDetailEvent {
  const OrderCancelRequested();
}

class TenderAddRequested extends OrderDetailEvent {
  const TenderAddRequested(this.kind, this.amountMinor);

  final String kind;
  final int amountMinor;

  @override
  List<Object?> get props => [kind, amountMinor];
}

class TenderSettleRequested extends OrderDetailEvent {
  const TenderSettleRequested(this.tenderId);

  final String tenderId;

  @override
  List<Object?> get props => [tenderId];
}

class OrderRefundRequested extends OrderDetailEvent {
  const OrderRefundRequested({this.amountMinor, required this.toStoreCredit});

  final int? amountMinor;
  final bool toStoreCredit;

  @override
  List<Object?> get props => [amountMinor, toStoreCredit];
}
