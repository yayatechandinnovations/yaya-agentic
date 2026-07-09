import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/error/failure.dart';
import '../../domain/entities/catalog_entities.dart';
import '../../domain/usecases/catalog_usecases.dart';

part 'products_event.dart';
part 'products_state.dart';

/// Products of one catalog: authoring, archive/restore, CSV import/export.
class ProductsBloc extends Bloc<ProductsEvent, ProductsState> {
  ProductsBloc({
    required ListProducts listProducts,
    required CreateProduct createProduct,
    required UpdateProduct updateProduct,
    required ArchiveProduct archiveProduct,
    required RestoreProduct restoreProduct,
    required ImportProductsCsv importCsv,
    required ExportProductsCsv exportCsv,
  })  : _listProducts = listProducts,
        _createProduct = createProduct,
        _updateProduct = updateProduct,
        _archiveProduct = archiveProduct,
        _restoreProduct = restoreProduct,
        _importCsv = importCsv,
        _exportCsv = exportCsv,
        super(const ProductsState()) {
    on<ProductsRequested>(_onRequested);
    on<ProductCreateRequested>(_onCreate);
    on<ProductUpdateRequested>(_onUpdate);
    on<ProductArchiveRequested>(_onArchive);
    on<ProductRestoreRequested>(_onRestore);
    on<ProductsImportRequested>(_onImport);
    on<ProductsExportRequested>(_onExport);
  }

  final ListProducts _listProducts;
  final CreateProduct _createProduct;
  final UpdateProduct _updateProduct;
  final ArchiveProduct _archiveProduct;
  final RestoreProduct _restoreProduct;
  final ImportProductsCsv _importCsv;
  final ExportProductsCsv _exportCsv;

  Future<void> _onRequested(ProductsRequested event, Emitter<ProductsState> emit) async {
    emit(state.copyWith(status: ProductsStatus.loading, catalogId: event.catalogId));
    try {
      final products = await _listProducts(event.catalogId);
      emit(state.copyWith(status: ProductsStatus.loaded, products: products));
    } on Failure catch (f) {
      emit(state.copyWith(status: ProductsStatus.failure, error: f.message));
    }
  }

  Future<void> _onCreate(ProductCreateRequested event, Emitter<ProductsState> emit) async {
    await _mutate(emit, () => _createProduct(state.catalogId, event.draft));
  }

  Future<void> _onUpdate(ProductUpdateRequested event, Emitter<ProductsState> emit) async {
    await _mutate(emit, () => _updateProduct(event.productId, event.draft));
  }

  Future<void> _onArchive(ProductArchiveRequested event, Emitter<ProductsState> emit) async {
    await _mutate(emit, () => _archiveProduct(event.productId));
  }

  Future<void> _onRestore(ProductRestoreRequested event, Emitter<ProductsState> emit) async {
    await _mutate(emit, () => _restoreProduct(event.productId));
  }

  Future<void> _onImport(ProductsImportRequested event, Emitter<ProductsState> emit) async {
    try {
      final result = await _importCsv(state.catalogId, event.csv);
      emit(state.copyWith(importResult: result));
      add(ProductsRequested(state.catalogId));
    } on Failure catch (f) {
      emit(state.copyWith(status: ProductsStatus.failure, error: f.message));
    }
  }

  Future<void> _onExport(ProductsExportRequested event, Emitter<ProductsState> emit) async {
    try {
      final csv = await _exportCsv(state.catalogId);
      emit(state.copyWith(exportedCsv: csv));
    } on Failure catch (f) {
      emit(state.copyWith(status: ProductsStatus.failure, error: f.message));
    }
  }

  Future<void> _mutate(Emitter<ProductsState> emit, Future<Object> Function() action) async {
    try {
      await action();
      add(ProductsRequested(state.catalogId));
    } on Failure catch (f) {
      emit(state.copyWith(status: ProductsStatus.failure, error: f.message));
    }
  }
}
