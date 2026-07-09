part of 'store_picker_bloc.dart';

enum StorePickerStatus { initial, loading, loaded, failure }

class StorePickerState extends Equatable {
  const StorePickerState({
    this.status = StorePickerStatus.initial,
    this.tenantId = '',
    this.stores = const [],
    this.error,
  });

  final StorePickerStatus status;
  final String tenantId;
  final List<Store> stores;
  final String? error;

  StorePickerState copyWith({
    StorePickerStatus? status,
    String? tenantId,
    List<Store>? stores,
    String? error,
  }) =>
      StorePickerState(
        status: status ?? this.status,
        tenantId: tenantId ?? this.tenantId,
        stores: stores ?? this.stores,
        error: error,
      );

  @override
  List<Object?> get props => [status, tenantId, stores, error];
}
