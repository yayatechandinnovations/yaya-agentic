part of 'store_picker_bloc.dart';

sealed class StorePickerEvent extends Equatable {
  const StorePickerEvent();

  @override
  List<Object?> get props => [];
}

class StorePickerTenantSubmitted extends StorePickerEvent {
  const StorePickerTenantSubmitted(this.tenantId);

  final String tenantId;

  @override
  List<Object?> get props => [tenantId];
}

class StoreCreateRequested extends StorePickerEvent {
  const StoreCreateRequested(this.displayName, this.currency);

  final String displayName;
  final String currency;

  @override
  List<Object?> get props => [displayName, currency];
}
