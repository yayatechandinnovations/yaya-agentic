import 'package:dio/dio.dart';

class ShipmentRemoteDataSource {
  ShipmentRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> ofOrder(String orderId) async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/orders/$orderId/shipments');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> create(
      String orderId, List<Map<String, dynamic>> lines, String? locationId) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/orders/$orderId/shipments',
      data: {
        if (lines.isNotEmpty) 'lines': lines,
        if (locationId != null && locationId.isNotEmpty) 'locationId': locationId,
      },
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> pick(String id) async {
    final res = await _dio.post<Map<String, dynamic>>('/v1/sales/admin/shipments/$id/pick');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> pack(String id, Map<String, dynamic>? parcel) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/shipments/$id/pack',
      data: parcel == null ? null : {'parcel': parcel},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> dispatch(
      String id, String? trackingNumber, String? trackingUrl) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/shipments/$id/dispatch',
      data: {
        if (trackingNumber != null && trackingNumber.isNotEmpty)
          'trackingNumber': trackingNumber,
        if (trackingUrl != null && trackingUrl.isNotEmpty) 'trackingUrl': trackingUrl,
      },
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> advanceDelivery(String id, String status,
      Map<String, dynamic>? proof, String? description, String? location) async {
    final res = await _dio.put<Map<String, dynamic>>(
      '/v1/sales/admin/shipments/$id/delivery-status',
      data: {
        'status': status,
        if (proof != null && proof.isNotEmpty) 'proof': proof,
        if (description != null && description.isNotEmpty) 'description': description,
        if (location != null && location.isNotEmpty) 'location': location,
      },
    );
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> tracking(String id) async {
    final res = await _dio.get<List<dynamic>>('/v1/sales/admin/shipments/$id/tracking');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> assign(String id, String? agentId) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/shipments/$id/assign',
      data: agentId == null ? null : {'agentId': agentId},
    );
    return res.data ?? const {};
  }
}
