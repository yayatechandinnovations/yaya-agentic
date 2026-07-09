import 'package:dio/dio.dart';

class RmaRemoteDataSource {
  RmaRemoteDataSource(this._dio);

  final Dio _dio;

  Future<Map<String, dynamic>> request(
      String orderId, List<Map<String, dynamic>> lines, String? reason) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/rmas', data: {
      'orderId': orderId,
      'lines': lines,
      if (reason != null && reason.isNotEmpty) 'reason': reason,
    });
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> list({String? status}) async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/rmas', queryParameters: {
      if (status != null && status.isNotEmpty) 'status': status,
    });
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> detail(String id) async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/sales/admin/rmas/$id');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> authorize(String id) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/rmas/$id/authorize');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> reject(String id, String? reason) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/rmas/$id/reject',
      data: reason == null ? null : {'reason': reason},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> cancel(String id) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/rmas/$id/cancel');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> setReturnShipment(String id, Map<String, dynamic> body) async {
    final res = await _dio.put<Map<String, dynamic>>(
      '/v1/sales/admin/rmas/$id/return-shipment',
      data: body,
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> receive(String id, Map<String, dynamic> body) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/rmas/$id/receive', data: body);
    return res.data ?? const {};
  }
}
