import 'package:dio/dio.dart';

class StoreRemoteDataSource {
  StoreRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> storesOf(String tenantId) async {
    final res = await _dio.get<List<dynamic>>(
      '/v1/sales/admin/stores',
      queryParameters: {'tenantId': tenantId},
    );
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createStore(
      String tenantId, String displayName, String defaultCurrency) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/stores', data: {
      'tenantId': tenantId,
      'displayName': displayName,
      'defaultCurrency': defaultCurrency,
    });
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> store(String id) async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/sales/admin/stores/$id');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> updatePolicies(String id, Map<String, dynamic> policies) async {
    final res = await _dio.put<Map<String, dynamic>>(
      '/v1/sales/admin/stores/$id/policies',
      data: policies,
    );
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> locations() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/locations');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addLocation(String label, String kind, bool sellable) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/locations', data: {
      'label': label,
      'kind': kind,
      'sellable': sellable,
    });
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> channels() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/channels');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addChannel(String kind, String? label) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/channels', data: {
      'kind': kind,
      if (label != null && label.isNotEmpty) 'label': label,
    });
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> regions() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/regions');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addRegion(String name, String currency,
      Map<String, dynamic> tax, List<String> tenders) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/regions', data: {
      'name': name,
      'currency': currency,
      'tax': tax,
      'tenders': tenders,
    });
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> updateRegion(
      String id, String name, Map<String, dynamic> tax, List<String> tenders) async {
    final res = await _dio.put<Map<String, dynamic>>('/v1/sales/admin/regions/$id', data: {
      'name': name,
      'tax': tax,
      'tenders': tenders,
    });
    return res.data ?? const {};
  }
}
