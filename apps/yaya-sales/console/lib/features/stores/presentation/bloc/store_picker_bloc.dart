import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/store_entities.dart';
import '../../domain/usecases/store_usecases.dart';

part 'store_picker_event.dart';
part 'store_picker_state.dart';

/// Loads the stores of a tenant and creates new ones. The page owns the actual
/// selection (it writes the StoreContext); this bloc owns the data.
class StorePickerBloc extends Bloc<StorePickerEvent, StorePickerState> {
  StorePickerBloc({required ListStores listStores, required CreateStore createStore})
      : _listStores = listStores,
        _createStore = createStore,
        super(const StorePickerState()) {
    on<StorePickerTenantSubmitted>(_onTenantSubmitted);
    on<StoreCreateRequested>(_onCreateRequested);
  }

  final ListStores _listStores;
  final CreateStore _createStore;

  Future<void> _onTenantSubmitted(
      StorePickerTenantSubmitted event, Emitter<StorePickerState> emit) async {
    emit(state.copyWith(status: StorePickerStatus.loading, tenantId: event.tenantId));
    try {
      final stores = await _listStores(event.tenantId);
      emit(state.copyWith(status: StorePickerStatus.loaded, stores: stores));
    } on Failure catch (f) {
      emit(state.copyWith(status: StorePickerStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreateRequested(
      StoreCreateRequested event, Emitter<StorePickerState> emit) async {
    try {
      await _createStore(state.tenantId, event.displayName, event.currency);
      add(StorePickerTenantSubmitted(state.tenantId));
    } on Failure catch (f) {
      emit(state.copyWith(status: StorePickerStatus.failure, error: f.message));
    }
  }
}
