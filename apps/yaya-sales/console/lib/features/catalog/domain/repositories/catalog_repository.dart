import '../entities/catalog_entities.dart';

abstract class CatalogRepository {
  Future<List<Catalog>> catalogs();
  Future<Catalog> createCatalog(String displayName);
  Future<Catalog> updateCatalog(String catalogId, {String? displayName, String? coverUrl});
  Future<Catalog> publish(String catalogId);

  Future<List<Product>> products(String catalogId);
  Future<Product> createProduct(String catalogId, ProductDraft draft);
  Future<Product> updateProduct(String productId, ProductDraft draft);
  Future<Product> archive(String productId);
  Future<Product> restore(String productId);

  Future<ImportResult> importCsv(String catalogId, String csv);
  Future<String> exportCsv(String catalogId);
}
