import 'package:dio/dio.dart';

class PurchasingRemoteDataSource {
  PurchasingRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> suppliers() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/suppliers');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createSupplier(
      String name, Map<String, dynamic> contact) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/suppliers',
        data: {'name': name, 'contact': contact});
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> updateSupplier(
      String id, String name, Map<String, dynamic> contact) async {
    final res = await _dio.put<Map<String, dynamic>>('/v1/sales/admin/suppliers/$id',
        data: {'name': name, 'contact': contact});
    return res.data ?? const {};
  }

  Future<void> deleteSupplier(String id) async {
    await _dio.delete<Map<String, dynamic>>('/v1/sales/admin/suppliers/$id');
  }

  Future<List<Map<String, dynamic>>> purchaseOrders({String? status}) async {
    final res =
        await _dio.get<List<dynamic>>('/v1/sales/admin/purchase-orders', queryParameters: {
      if (status != null && status.isNotEmpty) 'status': status,
    });
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createPo(Map<String, dynamic> body) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/purchase-orders', data: body);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> place(String id) async {
    final res = await _dio
        .post<Map<String, dynamic>>('/v1/sales/admin/purchase-orders/$id/place');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> cancel(String id) async {
    final res = await _dio
        .post<Map<String, dynamic>>('/v1/sales/admin/purchase-orders/$id/cancel');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> receive(String id, List<Map<String, dynamic>> lines) async {
    final res = await _dio.post<Map<String, dynamic>>(
        '/v1/sales/admin/purchase-orders/$id/receive',
        data: {'lines': lines});
    return res.data ?? const {};
  }
}
