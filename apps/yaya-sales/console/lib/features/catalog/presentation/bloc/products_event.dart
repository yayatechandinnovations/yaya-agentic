part of 'products_bloc.dart';

sealed class ProductsEvent extends Equatable {
  const ProductsEvent();

  @override
  List<Object?> get props => [];
}

class ProductsRequested extends ProductsEvent {
  const ProductsRequested(this.catalogId);

  final String catalogId;

  @override
  List<Object?> get props => [catalogId];
}

class ProductCreateRequested extends ProductsEvent {
  const ProductCreateRequested(this.draft);

  final ProductDraft draft;

  @override
  List<Object?> get props => [draft];
}

class ProductUpdateRequested extends ProductsEvent {
  const ProductUpdateRequested(this.productId, this.draft);

  final String productId;
  final ProductDraft draft;

  @override
  List<Object?> get props => [productId, draft];
}

class ProductArchiveRequested extends ProductsEvent {
  const ProductArchiveRequested(this.productId);

  final String productId;

  @override
  List<Object?> get props => [productId];
}

class ProductRestoreRequested extends ProductsEvent {
  const ProductRestoreRequested(this.productId);

  final String productId;

  @override
  List<Object?> get props => [productId];
}

class ProductsImportRequested extends ProductsEvent {
  const ProductsImportRequested(this.csv);

  final String csv;

  @override
  List<Object?> get props => [csv];
}

class ProductsExportRequested extends ProductsEvent {
  const ProductsExportRequested();
}
