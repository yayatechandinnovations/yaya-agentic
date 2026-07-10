import 'package:dio/dio.dart';

class OrgRemoteDataSource {
  OrgRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> list() async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/orgs');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> create(Map<String, dynamic> draft) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs',
      data: draft,
    );
    return res.data ?? const {};
  }

  Future<Map<String, dynamic>> update(String org, Map<String, dynamic> draft) async {
    final res = await _dio.put<Map<String, dynamic>>(
      '/v1/identity/admin/orgs/$org',
      data: draft,
    );
    return res.data ?? const {};
  }
}
