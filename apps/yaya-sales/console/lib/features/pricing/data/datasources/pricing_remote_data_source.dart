import 'package:dio/dio.dart';

class PricingRemoteDataSource {
  PricingRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> priceLists() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/price-lists');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createPriceList(Map<String, dynamic> body) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/price-lists', data: body);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> updatePriceList(String id, Map<String, dynamic> body) async {
    final res =
        await _dio.put<Map<String, dynamic>>('/v1/sales/admin/price-lists/$id', data: body);
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> promotions() async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/promotions');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> createPromotion(Map<String, dynamic> body) async {
    final res =
        await _dio.post<Map<String, dynamic>>('/v1/sales/admin/promotions', data: body);
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> updatePromotion(String id, Map<String, dynamic> body) async {
    final res =
        await _dio.put<Map<String, dynamic>>('/v1/sales/admin/promotions/$id', data: body);
    return res.data ?? const {};
  }
}
