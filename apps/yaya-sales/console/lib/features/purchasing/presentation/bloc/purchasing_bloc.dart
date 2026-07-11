import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../../catalog/domain/entities/catalog_entities.dart';
import '../../../catalog/domain/usecases/catalog_usecases.dart';
import '../../../stores/domain/entities/store_entities.dart';
import '../../../stores/domain/usecases/store_usecases.dart';
import '../../domain/entities/purchasing_entities.dart';
import '../../domain/usecases/purchasing_usecases.dart';

part 'purchasing_event.dart';
part 'purchasing_state.dart';

/// Purchasing desk: supplier CRUD + the PO lifecycle (create/place/cancel/
/// receive with partial receipts).
class PurchasingBloc extends Bloc<PurchasingEvent, PurchasingState> {
  PurchasingBloc({
    required ListSuppliers listSuppliers,
    required CreateSupplier createSupplier,
    required UpdateSupplier updateSupplier,
    required DeleteSupplier deleteSupplier,
    required ListPurchaseOrders listPos,
    required CreatePurchaseOrder createPo,
    required PlacePurchaseOrder placePo,
    required CancelPurchaseOrder cancelPo,
    required ReceivePurchaseOrder receivePo,
    required ListLocations listLocations,
    required ListCatalogs listCatalogs,
    required ListProducts listProducts,
  })  : _listSuppliers = listSuppliers,
        _createSupplier = createSupplier,
        _updateSupplier = updateSupplier,
        _deleteSupplier = deleteSupplier,
        _listPos = listPos,
        _createPo = createPo,
        _placePo = placePo,
        _cancelPo = cancelPo,
        _receivePo = receivePo,
        _listLocations = listLocations,
        _listCatalogs = listCatalogs,
        _listProducts = listProducts,
        super(const PurchasingState()) {
    on<PurchasingRequested>(_onRequested);
    on<SupplierSaveRequested>(_onSupplierSave);
    on<SupplierDeleteRequested>(_onSupplierDelete);
    on<PoCreateRequested>(_onPoCreate);
    on<PoPlaceRequested>(_onPoPlace);
    on<PoCancelRequested>(_onPoCancel);
    on<PoReceiveRequested>(_onPoReceive);
  }

  final ListSuppliers _listSuppliers;
  final CreateSupplier _createSupplier;
  final UpdateSupplier _updateSupplier;
  final DeleteSupplier _deleteSupplier;
  final ListPurchaseOrders _listPos;
  final CreatePurchaseOrder _createPo;
  final PlacePurchaseOrder _placePo;
  final CancelPurchaseOrder _cancelPo;
  final ReceivePurchaseOrder _receivePo;
  final ListLocations _listLocations;
  final ListCatalogs _listCatalogs;
  final ListProducts _listProducts;

  Future<void> _onRequested(
      PurchasingRequested event, Emitter<PurchasingState> emit) async {
    emit(state.copyWith(status: PurchasingStatus.loading, statusFilter: event.statusFilter));
    try {
      final results = await Future.wait<Object>([
        _listSuppliers(),
        _listPos(status: event.statusFilter),
        _listLocations(),
        _loadSkuOptions(),
      ]);
      emit(state.copyWith(
        status: PurchasingStatus.loaded,
        suppliers: results[0] as List<Supplier>,
        purchaseOrders: results[1] as List<PurchaseOrder>,
        locations: results[2] as List<StoreLocation>,
        skuOptions: results[3] as List<Variant>,
      ));
    } on Failure catch (f) {
      emit(state.copyWith(status: PurchasingStatus.failure, error: f.message));
    }
  }

  /// Flattens every catalog's product variants into the SKU picker options.
  Future<List<Variant>> _loadSkuOptions() async {
    final catalogs = await _listCatalogs();
    final variants = <Variant>[];
    for (final catalog in catalogs) {
      final products = await _listProducts(catalog.id);
      for (final product in products) {
        variants.addAll(product.variants);
      }
    }
    return variants;
  }

  Future<void> _onSupplierSave(
      SupplierSaveRequested event, Emitter<PurchasingState> emit) async {
    await _mutate(
        emit,
        () => event.id == null
            ? _createSupplier(event.name, event.contact)
            : _updateSupplier(event.id!, event.name, event.contact));
  }

  Future<void> _onSupplierDelete(
      SupplierDeleteRequested event, Emitter<PurchasingState> emit) async {
    await _mutate(emit, () async {
      await _deleteSupplier(event.id);
      return true;
    });
  }

  Future<void> _onPoCreate(PoCreateRequested event, Emitter<PurchasingState> emit) async {
    await _mutate(emit,
        () => _createPo(event.supplierId, event.locationId, event.lines, event.expectedAt));
  }

  Future<void> _onPoPlace(PoPlaceRequested event, Emitter<PurchasingState> emit) async {
    await _mutate(emit, () => _placePo(event.poId));
  }

  Future<void> _onPoCancel(PoCancelRequested event, Emitter<PurchasingState> emit) async {
    await _mutate(emit, () => _cancelPo(event.poId));
  }

  Future<void> _onPoReceive(PoReceiveRequested event, Emitter<PurchasingState> emit) async {
    await _mutate(emit, () => _receivePo(event.poId, event.lines));
  }

  Future<void> _mutate(
      Emitter<PurchasingState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(PurchasingRequested(statusFilter: state.statusFilter));
    } on Failure catch (f) {
      emit(state.copyWith(status: PurchasingStatus.failure, error: f.message));
    }
  }
}
