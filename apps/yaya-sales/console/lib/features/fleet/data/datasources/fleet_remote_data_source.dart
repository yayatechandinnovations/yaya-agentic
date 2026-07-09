import 'package:dio/dio.dart';

class FleetRemoteDataSource {
  FleetRemoteDataSource(this._dio);

  final Dio _dio;

  Future<Map<String, dynamic>> mintInvite(int? ttlHours) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/delivery/agents/invites',
      data: ttlHours == null ? null : {'ttlHours': ttlHours},
    );
    return res.data ?? const {};
  }

  Future<List<Map<String, dynamic>>> agents({String? status}) async {
    final res =
        await _dio.get<List<dynamic>>('/v1/sales/admin/delivery/agents', queryParameters: {
      if (status != null && status.isNotEmpty) 'status': status,
    });
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> agent(String id) async {
    final res =
        await _dio.get<Map<String, dynamic>>('/v1/sales/admin/delivery/agents/$id');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> documentContent(String agentId, String documentId) async {
    final res = await _dio.get<Map<String, dynamic>>(
        '/v1/sales/admin/delivery/agents/$agentId/documents/$documentId/content');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> approve(String id) async {
    final res = await _dio
        .post<Map<String, dynamic>>('/v1/sales/admin/delivery/agents/$id/approve');
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> reject(String id, String? reason) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/delivery/agents/$id/reject',
      data: reason == null ? null : {'reason': reason},
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> suspend(String id, String? reason) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/sales/admin/delivery/agents/$id/suspend',
      data: reason == null ? null : {'reason': reason},
    );
    return res.data ?? const {};
  }
}
