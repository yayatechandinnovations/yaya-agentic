part of 'products_bloc.dart';

enum ProductsStatus { initial, loading, loaded, failure }

class ProductsState extends Equatable {
  const ProductsState({
    this.status = ProductsStatus.initial,
    this.catalogId = '',
    this.products = const [],
    this.importResult,
    this.exportedCsv,
    this.error,
  });

  final ProductsStatus status;
  final String catalogId;
  final List<Product> products;
  final ImportResult? importResult;
  final String? exportedCsv;
  final String? error;

  ProductsState copyWith({
    ProductsStatus? status,
    String? catalogId,
    List<Product>? products,
    ImportResult? importResult,
    String? exportedCsv,
    String? error,
  }) =>
      ProductsState(
        status: status ?? this.status,
        catalogId: catalogId ?? this.catalogId,
        products: products ?? this.products,
        importResult: importResult,
        exportedCsv: exportedCsv,
        error: error,
      );

  @override
  List<Object?> get props => [status, catalogId, products, importResult, exportedCsv, error];
}
