import 'package:dio/dio.dart';

class DraftRemoteDataSource {
  DraftRemoteDataSource(this._dio);

  final Dio _dio;

  Future<Map<String, dynamic>> create(Map<String, dynamic> body) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/draft-orders', data: body);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> update(String orderId, Map<String, dynamic> body) async {
    final res = await _dio.put<Map<String, dynamic>>(
        '/v1/sales/admin/draft-orders/$orderId',
        data: body);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> send(String orderId, Map<String, dynamic> body) async {
    final res = await _dio.post<Map<String, dynamic>>(
        '/v1/sales/admin/draft-orders/$orderId/send',
        data: body);
    return res.data ?? const {};
  }
}
