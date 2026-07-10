import 'package:dio/dio.dart';

class PolicyAdminDataSource {
  PolicyAdminDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> list(String org) async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/orgs/$org/policies');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> create(String org, Map<String, dynamic> body) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/$org/policies',
      data: body,
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> setActive(String org, String policyId, bool active) async {
    final res = await _dio.put<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/$org/policies/$policyId/active',
      data: {'active': active},
    );
    return res.data ?? const {};
  }
}
