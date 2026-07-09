import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../catalog/domain/entities/catalog_entities.dart';
import '../../../catalog/domain/usecases/catalog_usecases.dart';
import '../../../orders/domain/entities/order_entities.dart';
import '../../../orders/domain/usecases/order_usecases.dart';
import '../../domain/entities/draft_entities.dart';
import '../../domain/usecases/draft_usecases.dart';

part 'drafts_event.dart';
part 'drafts_state.dart';

/// Draft orders: list (orders?status=DRAFT), build/edit lines with a SKU
/// picker fed from the catalogs, send for payment, cancel via order cancel.
class DraftsBloc extends Bloc<DraftsEvent, DraftsState> {
  DraftsBloc({
    required ListOrders listOrders,
    required CreateDraft createDraft,
    required UpdateDraft updateDraft,
    required SendDraft sendDraft,
    required CancelOrder cancelOrder,
    required ListCatalogs listCatalogs,
    required ListProducts listProducts,
  })  : _listOrders = listOrders,
        _createDraft = createDraft,
        _updateDraft = updateDraft,
        _sendDraft = sendDraft,
        _cancelOrder = cancelOrder,
        _listCatalogs = listCatalogs,
        _listProducts = listProducts,
        super(const DraftsState()) {
    on<DraftsRequested>(_onRequested);
    on<DraftSkusRequested>(_onSkusRequested);
    on<DraftCreateRequested>(_onCreate);
    on<DraftUpdateRequested>(_onUpdate);
    on<DraftSendRequested>(_onSend);
    on<DraftCancelRequested>(_onCancel);
    on<DraftOutcomeDismissed>(_onOutcomeDismissed);
  }

  final ListOrders _listOrders;
  final CreateDraft _createDraft;
  final UpdateDraft _updateDraft;
  final SendDraft _sendDraft;
  final CancelOrder _cancelOrder;
  final ListCatalogs _listCatalogs;
  final ListProducts _listProducts;

  Future<void> _onRequested(DraftsRequested event, Emitter<DraftsState> emit) async {
    emit(state.copyWith(status: DraftsStatus.loading));
    try {
      final drafts = await _listOrders(status: 'DRAFT');
      emit(state.copyWith(status: DraftsStatus.loaded, drafts: drafts));
    } on Failure catch (f) {
      emit(state.copyWith(status: DraftsStatus.failure, error: f.message));
    }
  }

  /// Flattens every catalog's product variants into the SKU picker options.
  Future<void> _onSkusRequested(DraftSkusRequested event, Emitter<DraftsState> emit) async {
    try {
      final catalogs = await _listCatalogs();
      final variants = <Variant>[];
      for (final catalog in catalogs) {
        final products = await _listProducts(catalog.id);
        for (final product in products) {
          variants.addAll(product.variants);
        }
      }
      emit(state.copyWith(skuOptions: variants));
    } on Failure catch (f) {
      emit(state.copyWith(status: DraftsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(DraftCreateRequested event, Emitter<DraftsState> emit) async {
    try {
      await _createDraft(
        channel: event.channel,
        regionId: event.regionId,
        customerRef: event.customerRef,
        note: event.note,
        lines: event.lines,
      );
      add(const DraftsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: DraftsStatus.failure, error: f.message));
    }
  }

  Future<void> _onUpdate(DraftUpdateRequested event, Emitter<DraftsState> emit) async {
    try {
      await _updateDraft(event.orderId, event.lines);
      add(const DraftsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: DraftsStatus.failure, error: f.message));
    }
  }

  Future<void> _onSend(DraftSendRequested event, Emitter<DraftsState> emit) async {
    try {
      final outcome = await _sendDraft(
        event.orderId,
        tender: event.tender,
        applyStoreCredit: event.applyStoreCredit,
        returnUrl: event.returnUrl,
        customerRef: event.customerRef,
      );
      emit(state.copyWith(lastOutcome: outcome));
      add(const DraftsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: DraftsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCancel(DraftCancelRequested event, Emitter<DraftsState> emit) async {
    try {
      await _cancelOrder(event.orderId);
      add(const DraftsRequested());
    } on Failure catch (f) {
      emit(state.copyWith(status: DraftsStatus.failure, error: f.message));
    }
  }

  void _onOutcomeDismissed(DraftOutcomeDismissed event, Emitter<DraftsState> emit) {
    emit(state.copyWith());
  }
}
