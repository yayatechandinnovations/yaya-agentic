import 'package:dio/dio.dart';

class OrderRemoteDataSource {
  OrderRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> orders({String? status}) async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/orders', queryParameters: {
      if (status != null && status.isNotEmpty) 'status': status,
    });
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> detail(String id) async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/sales/admin/orders/$id');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> cancel(String id) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/orders/$id/cancel');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> addTender(String orderId, String kind, int amountMinor) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/orders/$orderId/tenders',
      data: {'kind': kind, 'amountMinor': amountMinor},
    );
    return res.data ?? const {};
  }

  Future<void> settleTender(String orderId, String tenderId) async {
    await _dio.post<Map<String, dynamic>>(
        '/v1/sales/admin/orders/$orderId/tenders/$tenderId/settle');
  }

  Future<Map<String, dynamic>> refund(String orderId, int? amountMinor, String to) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/orders/$orderId/refund',
      data: {
        if (amountMinor != null) 'amountMinor': amountMinor,
        'to': to,
      },
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> storeCreditBalance(String customerRef, String currency) async {
    final res = await _dio.get<Map<String, dynamic>>(
      '/v1/sales/admin/store-credit/$customerRef',
      queryParameters: {'currency': currency},
    );
    return res.data ?? const {};
  }

  Future<void> issueStoreCredit(
      String customerRef, int amountMinor, String currency, String? reason) async {
    await _dio.post<Map<String, dynamic>>('/v1/sales/admin/store-credit', data: {
      'customerRef': customerRef,
      'amountMinor': amountMinor,
      'currency': currency,
      if (reason != null && reason.isNotEmpty) 'reason': reason,
    });
  }
}
