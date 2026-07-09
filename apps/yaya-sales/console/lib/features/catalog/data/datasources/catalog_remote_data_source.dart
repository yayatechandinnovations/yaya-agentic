import 'package:dio/dio.dart';

class CatalogRemoteDataSource {
  CatalogRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> catalogs() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/catalogs');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createCatalog(String displayName) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/catalogs',
      data: {'displayName': displayName},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> publish(String id) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/catalogs/$id/publish');
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> products(String catalogId) async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/catalogs/$catalogId/products');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createProduct(
      String catalogId, Map<String, dynamic> draft) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/catalogs/$catalogId/products',
      data: draft,
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> updateProduct(
      String productId, Map<String, dynamic> draft) async {
    final res = await _dio.put<Map<String, dynamic>>(
      '/v1/sales/admin/products/$productId',
      data: draft,
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> archive(String productId) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/products/$productId/archive');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> restore(String productId) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/products/$productId/restore');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> importCsv(String catalogId, String csv) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/catalogs/$catalogId/products/bulk',
      data: csv,
      options: Options(contentType: 'text/csv'),
    );
    return res.data ?? const {};
  }

  Future<String> exportCsv(String catalogId) async {
    final res = await _dio.get<String>(
      '/v1/sales/admin/catalogs/$catalogId/products/export.csv',
      options: Options(responseType: ResponseType.plain),
    );
    return res.data ?? '';
  }
}
