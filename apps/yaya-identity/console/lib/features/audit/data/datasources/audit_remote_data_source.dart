import 'package:dio/dio.dart';

class AuditRemoteDataSource {
  AuditRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> read(String org, int limit) async {
    final res = await _dio.get<List<dynamic>>(
      '/v1/identity/admin/orgs/$org/audit',
      queryParameters: {'limit': limit},
    );
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }
}
