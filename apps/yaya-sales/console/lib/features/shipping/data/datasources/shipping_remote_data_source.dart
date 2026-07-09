import 'package:dio/dio.dart';

class ShippingRemoteDataSource {
  ShippingRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> zones() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/shipping/zones');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addZone(Map<String, dynamic> body) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/shipping/zones',
        data: body);
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> methods() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/shipping/methods');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addMethod(String kind, String label) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/shipping/methods',
        data: {'kind': kind, 'label': label});
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> setMethodActive(String id, bool active) async {
    final res = await _dio.put<Map<String, dynamic>>(
        '/v1/sales/admin/shipping/methods/$id',
        data: {'active': active});
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> carriers() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/shipping/carriers');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addCarrier(
      String kind, String name, String? webhookSecret) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/shipping/carriers', data: {
      'kind': kind,
      'name': name,
      if (webhookSecret != null && webhookSecret.isNotEmpty) 'webhookSecret': webhookSecret,
    });
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> rates() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/shipping/rates');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> addRate(Map<String, dynamic> body) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/shipping/rates',
        data: body);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> addSlot(Map<String, dynamic> body) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/shipping/slots',
        data: body);
    return res.data ?? const {};
  }
}
