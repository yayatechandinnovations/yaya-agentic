import '../entities/catalog_entities.dart';
import '../repositories/catalog_repository.dart';

class ListCatalogs {
  const ListCatalogs(this._repo);
  final CatalogRepository _repo;
  Future<List<Catalog>> call() => _repo.catalogs();
}

class CreateCatalog {
  const CreateCatalog(this._repo);
  final CatalogRepository _repo;
  Future<Catalog> call(String displayName) => _repo.createCatalog(displayName);
}

class UpdateCatalog {
  const UpdateCatalog(this._repo);
  final CatalogRepository _repo;
  Future<Catalog> call(String catalogId, {String? displayName, String? coverUrl}) =>
      _repo.updateCatalog(catalogId, displayName: displayName, coverUrl: coverUrl);
}

class PublishCatalog {
  const PublishCatalog(this._repo);
  final CatalogRepository _repo;
  Future<Catalog> call(String catalogId) => _repo.publish(catalogId);
}

class ListProducts {
  const ListProducts(this._repo);
  final CatalogRepository _repo;
  Future<List<Product>> call(String catalogId) => _repo.products(catalogId);
}

class CreateProduct {
  const CreateProduct(this._repo);
  final CatalogRepository _repo;
  Future<Product> call(String catalogId, ProductDraft draft) =>
      _repo.createProduct(catalogId, draft);
}

class UpdateProduct {
  const UpdateProduct(this._repo);
  final CatalogRepository _repo;
  Future<Product> call(String productId, ProductDraft draft) =>
      _repo.updateProduct(productId, draft);
}

class ArchiveProduct {
  const ArchiveProduct(this._repo);
  final CatalogRepository _repo;
  Future<Product> call(String productId) => _repo.archive(productId);
}

class RestoreProduct {
  const RestoreProduct(this._repo);
  final CatalogRepository _repo;
  Future<Product> call(String productId) => _repo.restore(productId);
}

class ImportProductsCsv {
  const ImportProductsCsv(this._repo);
  final CatalogRepository _repo;
  Future<ImportResult> call(String catalogId, String csv) => _repo.importCsv(catalogId, csv);
}

class ExportProductsCsv {
  const ExportProductsCsv(this._repo);
  final CatalogRepository _repo;
  Future<String> call(String catalogId) => _repo.exportCsv(catalogId);
}
