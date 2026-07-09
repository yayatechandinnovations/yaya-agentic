import 'package:dio/dio.dart';

class ReportsRemoteDataSource {
  ReportsRemoteDataSource(this._dio);

  final Dio _dio;

  Map<String, dynamic> _window(DateTime? from, DateTime? to) => {
        if (from != null) 'from': from.toUtc().toIso8601String(),
        if (to != null) 'to': to.toUtc().toIso8601String(),
      };

  Future<Map<String, dynamic>> sales(DateTime? from, DateTime? to) async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/sales/admin/reports/sales',
        queryParameters: _window(from, to));
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> inventoryValuation() async {
    final res = await _dio
        .get<Map<String, dynamic>>('/v1/sales/admin/reports/inventory-valuation');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> margin(DateTime? from, DateTime? to) async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/sales/admin/reports/margin',
        queryParameters: _window(from, to));
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> lowStock() async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/sales/admin/reports/low-stock');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> codReconciliation(DateTime? from, DateTime? to) async {
    final res = await _dio.get<Map<String, dynamic>>(
        '/v1/sales/admin/reports/cod-reconciliation',
        queryParameters: _window(from, to));
    return res.data ?? const {};
  }
}
