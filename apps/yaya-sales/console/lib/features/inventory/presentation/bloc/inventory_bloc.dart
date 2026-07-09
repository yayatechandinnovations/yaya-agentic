import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/inventory_entities.dart';
import '../../domain/usecases/inventory_usecases.dart';

part 'inventory_event.dart';
part 'inventory_state.dart';

/// Levels table + per-SKU ledger + the ledger-movement actions. Reads come
/// from the summary projection; every write is a movement, so each action
/// refreshes the levels afterwards.
class InventoryBloc extends Bloc<InventoryEvent, InventoryState> {
  InventoryBloc({
    required ListLevels listLevels,
    required ReadLedger readLedger,
    required ReceiveStock receiveStock,
    required AdjustStock adjustStock,
    required TransferStock transferStock,
    required SetReorderPolicy setReorder,
    required SetBackorderPolicy setBackorder,
  })  : _listLevels = listLevels,
        _readLedger = readLedger,
        _receiveStock = receiveStock,
        _adjustStock = adjustStock,
        _transferStock = transferStock,
        _setReorder = setReorder,
        _setBackorder = setBackorder,
        super(const InventoryState()) {
    on<LevelsRequested>(_onLevelsRequested);
    on<LedgerRequested>(_onLedgerRequested);
    on<LedgerClosed>(_onLedgerClosed);
    on<ReceiveRequested>(_onReceive);
    on<AdjustRequested>(_onAdjust);
    on<TransferRequested>(_onTransfer);
    on<ReorderPolicyRequested>(_onReorder);
    on<BackorderPolicyRequested>(_onBackorder);
  }

  final ListLevels _listLevels;
  final ReadLedger _readLedger;
  final ReceiveStock _receiveStock;
  final AdjustStock _adjustStock;
  final TransferStock _transferStock;
  final SetReorderPolicy _setReorder;
  final SetBackorderPolicy _setBackorder;

  Future<void> _onLevelsRequested(
      LevelsRequested event, Emitter<InventoryState> emit) async {
    emit(state.copyWith(
      status: InventoryStatus.loading,
      locationFilter: event.location,
      lowOnly: event.lowOnly,
    ));
    try {
      final levels = await _listLevels(location: event.location, lowOnly: event.lowOnly);
      emit(state.copyWith(status: InventoryStatus.loaded, levels: levels));
    } on Failure catch (f) {
      emit(state.copyWith(status: InventoryStatus.failure, error: f.message));
    }
  }

  Future<void> _onLedgerRequested(
      LedgerRequested event, Emitter<InventoryState> emit) async {
    try {
      final movements = await _readLedger(event.sku, location: event.location);
      emit(state.copyWith(ledgerSku: event.sku, ledger: movements));
    } on Failure catch (f) {
      emit(state.copyWith(status: InventoryStatus.failure, error: f.message));
    }
  }

  void _onLedgerClosed(LedgerClosed event, Emitter<InventoryState> emit) {
    emit(state.copyWith(clearLedger: true));
  }

  Future<void> _onReceive(ReceiveRequested event, Emitter<InventoryState> emit) async {
    await _mutate(emit,
        () => _receiveStock(event.sku, event.location, event.qty, event.unitCostMinor,
            event.currency, event.poRef));
  }

  Future<void> _onAdjust(AdjustRequested event, Emitter<InventoryState> emit) async {
    await _mutate(emit, () => _adjustStock(event.sku, event.location, event.delta, event.reason));
  }

  Future<void> _onTransfer(TransferRequested event, Emitter<InventoryState> emit) async {
    await _mutate(emit, () => _transferStock(event.sku, event.from, event.to, event.qty));
  }

  Future<void> _onReorder(
      ReorderPolicyRequested event, Emitter<InventoryState> emit) async {
    await _mutate(emit, () => _setReorder(event.sku, event.location, event.point, event.qty));
  }

  Future<void> _onBackorder(
      BackorderPolicyRequested event, Emitter<InventoryState> emit) async {
    await _mutate(emit, () => _setBackorder(event.sku, event.location, event.policy));
  }

  Future<void> _mutate(Emitter<InventoryState> emit, Future<void> Function() action) async {
    try {
      await action();
      add(LevelsRequested(location: state.locationFilter, lowOnly: state.lowOnly));
    } on Failure catch (f) {
      emit(state.copyWith(status: InventoryStatus.failure, error: f.message));
    }
  }
}
