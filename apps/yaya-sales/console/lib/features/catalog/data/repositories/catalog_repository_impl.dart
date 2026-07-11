import 'package:dio/dio.dart';

import '../../../../core/network/error_mapper.dart';
import '../../domain/entities/catalog_entities.dart';
import '../../domain/repositories/catalog_repository.dart';
import '../datasources/catalog_remote_data_source.dart';
import '../models/catalog_models.dart';

class CatalogRepositoryImpl implements CatalogRepository {
  CatalogRepositoryImpl(this._remote);

  final CatalogRemoteDataSource _remote;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw mapDioError(e);
    }
  }

  @override
  Future<List<Catalog>> catalogs() =>
      _guard(() async => (await _remote.catalogs()).map(catalogFromJson).toList());

  @override
  Future<Catalog> createCatalog(String displayName) =>
      _guard(() async => catalogFromJson(await _remote.createCatalog(displayName)));

  @override
  Future<Catalog> updateCatalog(String catalogId, {String? displayName, String? coverUrl}) =>
      _guard(() async => catalogFromJson(await _remote.updateCatalog(catalogId, {
            if (displayName != null) 'displayName': displayName,
            if (coverUrl != null) 'coverUrl': coverUrl,
          })));

  @override
  Future<Catalog> publish(String catalogId) =>
      _guard(() async => catalogFromJson(await _remote.publish(catalogId)));

  @override
  Future<List<Product>> products(String catalogId) =>
      _guard(() async => (await _remote.products(catalogId)).map(productFromJson).toList());

  @override
  Future<Product> createProduct(String catalogId, ProductDraft draft) => _guard(
      () async => productFromJson(await _remote.createProduct(catalogId, draftToJson(draft))));

  @override
  Future<Product> updateProduct(String productId, ProductDraft draft) => _guard(
      () async => productFromJson(await _remote.updateProduct(productId, draftToJson(draft))));

  @override
  Future<Product> archive(String productId) =>
      _guard(() async => productFromJson(await _remote.archive(productId)));

  @override
  Future<Product> restore(String productId) =>
      _guard(() async => productFromJson(await _remote.restore(productId)));

  @override
  Future<ImportResult> importCsv(String catalogId, String csv) =>
      _guard(() async => importResultFromJson(await _remote.importCsv(catalogId, csv)));

  @override
  Future<String> exportCsv(String catalogId) => _guard(() => _remote.exportCsv(catalogId));
}
