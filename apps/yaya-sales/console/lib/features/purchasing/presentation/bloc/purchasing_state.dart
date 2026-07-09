part of 'purchasing_bloc.dart';

enum PurchasingStatus { initial, loading, loaded, failure }

class PurchasingState extends Equatable {
  const PurchasingState({
    this.status = PurchasingStatus.initial,
    this.suppliers = const [],
    this.purchaseOrders = const [],
    this.statusFilter,
    this.error,
  });

  final PurchasingStatus status;
  final List<Supplier> suppliers;
  final List<PurchaseOrder> purchaseOrders;
  final String? statusFilter;
  final String? error;

  PurchasingState copyWith({
    PurchasingStatus? status,
    List<Supplier>? suppliers,
    List<PurchaseOrder>? purchaseOrders,
    String? statusFilter,
    String? error,
  }) =>
      PurchasingState(
        status: status ?? this.status,
        suppliers: suppliers ?? this.suppliers,
        purchaseOrders: purchaseOrders ?? this.purchaseOrders,
        statusFilter: statusFilter ?? this.statusFilter,
        error: error,
      );

  @override
  List<Object?> get props => [status, suppliers, purchaseOrders, statusFilter, error];
}
