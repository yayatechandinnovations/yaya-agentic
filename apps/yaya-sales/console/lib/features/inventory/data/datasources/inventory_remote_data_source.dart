import 'package:dio/dio.dart';

class InventoryRemoteDataSource {
  InventoryRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> levels({String? location, bool lowOnly = false}) async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/inventory', queryParameters: {
      if (location != null && location.isNotEmpty) 'location': location,
      if (lowOnly) 'low': 'true',
    });
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<List<Map<String, dynamic>>> ledger(String sku, {String? location}) async {
    final res = await _dio.get<List<dynamic>>(
      '/v1/sales/admin/inventory/$sku/ledger',
      queryParameters: {
        if (location != null && location.isNotEmpty) 'location': location,
      },
    );
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<void> receive(String sku, String location, int qty, int unitCostMinor,
      String currency, String? poRef) async {
    await _dio.post<Map<String, dynamic>>('/v1/sales/admin/inventory/$sku/receive', data: {
      'location': location,
      'qty': qty,
      'unitCostMinor': unitCostMinor,
      'currency': currency,
      if (poRef != null && poRef.isNotEmpty) 'ref': poRef,
    });
  }

  Future<void> adjust(String sku, String location, int delta, String reason) async {
    await _dio.post<Map<String, dynamic>>('/v1/sales/admin/inventory/$sku/adjust', data: {
      'location': location,
      'delta': delta,
      'reason': reason,
    });
  }

  Future<void> transfer(String sku, String from, String to, int qty) async {
    await _dio.post<Map<String, dynamic>>('/v1/sales/admin/inventory/transfer', data: {
      'sku': sku,
      'from': from,
      'to': to,
      'qty': qty,
    });
  }

  Future<void> setReorder(String sku, String location, int point, int qty) async {
    await _dio.put<Map<String, dynamic>>('/v1/sales/admin/inventory/$sku/reorder', data: {
      'location': location,
      'point': point,
      'qty': qty,
    });
  }

  Future<void> setBackorder(String sku, String location, String policy) async {
    await _dio.put<Map<String, dynamic>>('/v1/sales/admin/inventory/$sku/backorder', data: {
      'location': location,
      'policy': policy,
    });
  }
}
