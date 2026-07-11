import 'package:dio/dio.dart';

class CustomerRemoteDataSource {
  CustomerRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> search(String? q) async {
    final res = await _dio.get<List<dynamic>>(
      '/v1/sales/admin/customers',
      queryParameters: {if (q != null && q.isNotEmpty) 'q': q},
    );
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> create(Map<String, dynamic> draft) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/customers', data: draft);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> update(String id, Map<String, dynamic> draft) async {
    final res =
        await _dio.put<Map<String, dynamic>>('/v1/sales/admin/customers/$id', data: draft);
    return res.data ?? const {};
  }

  Future<void> delete(String id) async {
    await _dio.delete<Map<String, dynamic>>('/v1/sales/admin/customers/$id');
  }

  Future<Map<String, dynamic>> addAddress(
      String id, String kind, Map<String, dynamic> address) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/customers/$id/addresses',
      data: {'kind': kind, 'address': address},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> removeAddress(String id, String addressId) async {
    final res = await _dio
        .delete<Map<String, dynamic>>('/v1/sales/admin/customers/$id/addresses/$addressId');
    return res.data ?? const {};
  }
}
