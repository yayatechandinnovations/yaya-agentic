part of 'rmas_bloc.dart';

enum RmasStatus { initial, loading, loaded, failure }

class RmasState extends Equatable {
  const RmasState({
    this.status = RmasStatus.initial,
    this.rmas = const [],
    this.paidOrders = const [],
    this.statusFilter,
    this.selected,
    this.lookupOrder,
    this.error,
  });

  final RmasStatus status;
  final List<Rma> rmas;
  final List<Order> paidOrders;
  final String? statusFilter;
  final Rma? selected;
  final Order? lookupOrder;
  final String? error;

  RmasState copyWith({
    RmasStatus? status,
    List<Rma>? rmas,
    List<Order>? paidOrders,
    String? statusFilter,
    Rma? selected,
    Order? lookupOrder,
    String? error,
  }) =>
      RmasState(
        status: status ?? this.status,
        rmas: rmas ?? this.rmas,
        paidOrders: paidOrders ?? this.paidOrders,
        statusFilter: statusFilter ?? this.statusFilter,
        selected: selected ?? this.selected,
        lookupOrder: lookupOrder ?? this.lookupOrder,
        error: error,
      );

  @override
  List<Object?> get props =>
      [status, rmas, paidOrders, statusFilter, selected, lookupOrder, error];
}
