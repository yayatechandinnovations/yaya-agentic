import 'package:dio/dio.dart';

class OrgRemoteDataSource {
  OrgRemoteDataSource(this._dio);

  final Dio _dio;

  Future<List<Map<String, dynamic>>> list() async {
    final res = await _dio.get<List<dynamic>>('/v1/identity/admin/orgs');
    return (res.data ?? []).cast<Map<String, dynamic>>();
  }

  Future<Map<String, dynamic>> create(String name) async {
    final res = await _dio.post<Map<String, dynamic>>(
      '/v1/identity/admin/orgs',
      data: {'name': name},
    );
    return res.data ?? const {};
  }
}
